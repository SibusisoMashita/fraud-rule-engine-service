package za.co.sibusiso.fraud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.persistence.JdbcTransactionStore;
import za.co.sibusiso.fraud.evaluation.rules.CumulativeSpendRule;
import za.co.sibusiso.fraud.evaluation.rules.HighAmountRule;
import za.co.sibusiso.fraud.evaluation.rules.ImpossibleTravelRule;
import za.co.sibusiso.fraud.evaluation.rules.RuleEngine;
import za.co.sibusiso.fraud.evaluation.rules.SmallPaymentBurstRule;
import za.co.sibusiso.fraud.evaluation.rules.TransactionVelocityRule;
import za.co.sibusiso.fraud.ingress.security.CredentialAuthenticator;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HistoricalEvaluationTest {
    private static final String PASSWORD = TestDatabase.PASSWORD, SECRET = "d".repeat(64);

    @Container
    static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(
                            DockerImageName.parse(TestImages.POSTGRES)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(TestImages.REDIS))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", PASSWORD);

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) throws Exception {
        TestDatabase.initialize(DATABASE);
        properties.add("fraud.cursor-signing-key", () -> "a".repeat(64));
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password", () -> PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired ServiceLimits limits;
    @MockitoSpyBean JdbcTransactionStore store;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String original =
            new StrictYaml()
                    .readText(
                            new org.springframework.core.io.ClassPathResource(
                                    "config/policy.example.yaml"));

    record Identity(String scope, String keyId) {
        String key() {
            return keyId + "." + SECRET;
        }
    }

    private JdbcTemplate database(String role) {
        return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), role, PASSWORD));
    }

    private Identity identity() {
        String id = UUID.randomUUID().toString();
        var who = new Identity("history-http-" + id, id);
        var admin = database("fraud_security_admin");
        admin.update(
                "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['INGEST','READ'],ARRAY[]::text[])",
                id,
                who.scope());
        admin.update(
                "INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')",
                id,
                id,
                HexFormat.of().formatHex(CredentialAuthenticator.digest(SECRET)));
        return who;
    }

    @AfterEach
    void restore() {
        reset(store);
        activate(original);
    }

    @Test
    void serialEventsUseStrictCountAndDecimalThresholdsIncludingFlaggedHistory() throws Exception {
        activate(
                original.replace("maxTransactions: 5", "maxTransactions: 2")
                        .replace("30000.00", "0.30"));
        var who = identity();
        for (int n = 1; n <= 4; n++) {
            var response = post(who, body("event-" + n, "customer", "card-" + n, "0.10"));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            var evaluation = json.readTree(response.body()).get("evaluation");
            assertThat(evaluation.get("contextHighWatermark").asLong()).isEqualTo(n - 1);
            assertThat(fact(evaluation, "TRANSACTION_VELOCITY", "count").asLong()).isEqualTo(n);
            assertThat(fact(evaluation, "CUMULATIVE_SPEND", "total").asDecimal())
                    .isEqualByComparingTo("0." + n);
            assertThat(status(evaluation, "TRANSACTION_VELOCITY"))
                    .isEqualTo(n > 2 ? "MATCH" : "NO_MATCH");
            assertThat(status(evaluation, "CUMULATIVE_SPEND"))
                    .isEqualTo(n > 3 ? "MATCH" : "NO_MATCH");
        }
        assertCounts(who, 4);
    }

    @Test
    void windowsLimitsThresholdsAndTogglesChangeLiveWithoutReinterpretingReplay() throws Exception {
        String narrow =
                original.replace("windowSeconds: 600", "windowSeconds: 5")
                        .replace("windowSeconds: 1800", "windowSeconds: 5")
                        .replace("maxTransactions: 5", "maxTransactions: 1")
                        .replace("30000.00", "0.15");
        activate(narrow);
        var who = identity();
        var time = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));
        doAnswer(invocation -> time.get()).when(store).databaseTime();
        assertThat(post(who, body("first", "customer", "card", "0.10")).statusCode())
                .isEqualTo(201);
        time.set(time.get().plusSeconds(10));
        var second = post(who, body("second", "customer", "card", "0.10"));
        assertThat(json.readTree(second.body()).at("/evaluation/decision").asString())
                .isEqualTo("CLEAR");
        String wider = narrow.replace("windowSeconds: 5", "windowSeconds: 20");
        String hash = activate(wider);
        String thirdInput = body("third", "customer", "card", "0.10");
        var third = post(who, thirdInput);
        var three = json.readTree(third.body()).get("evaluation");
        assertThat(three.get("policyHash").asString()).isEqualTo(hash);
        assertThat(fact(three, "TRANSACTION_VELOCITY", "count").asLong()).isEqualTo(3);
        assertThat(fact(three, "CUMULATIVE_SPEND", "total").asDecimal())
                .isEqualByComparingTo("0.30");
        String relaxed =
                wider.replace("maxTransactions: 1", "maxTransactions: 4").replace("0.15", "0.40");
        activate(relaxed);
        var four = post(who, body("fourth", "customer", "card", "0.10"));
        assertThat(json.readTree(four.body()).at("/evaluation/decision").asString())
                .isEqualTo("CLEAR");
        String velocityOff =
                relaxed.replace(
                        "transactionVelocity:\n    enabled: true",
                        "transactionVelocity:\n    enabled: false");
        activate(velocityOff);
        clearInvocations(store);
        var five = post(who, body("fifth", "customer", "card", "0.10"));
        assertThat(status(json.readTree(five.body()).get("evaluation"), "TRANSACTION_VELOCITY"))
                .isEqualTo("DISABLED");
        verify(store, never())
                .aggregate(
                        anyString(), anyString(), isA(HistoryQuery.Count.class), any(), anyLong());
        activate(
                velocityOff.replace(
                        "cumulativeSpend:\n    enabled: true",
                        "cumulativeSpend:\n    enabled: false"));
        clearInvocations(store);
        assertThat(post(who, body("sixth", "customer", "card", "0.10")).statusCode())
                .isEqualTo(201);
        verify(store, never()).aggregate(anyString(), anyString(), any(), any(), anyLong());
        var replay = post(who, thirdInput);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(third.body());
    }

    @Test
    void velocityCountsBothCurrenciesWhileSpendNeverMixesThem() throws Exception {
        String both =
                original.replace(
                                "  ZAR: {scale: 2, maxAmount: \"999999999999.99\"}",
                                "  ZAR: {scale: 2, maxAmount: \"999999999999.99\"}\n  USD: {scale: 2, maxAmount: \"999999999999.99\"}")
                        .replace("30000.00", "0.15")
                        .replace("maxTransactions: 5", "maxTransactions: 1")
                        .replaceAll("\\{ZAR: \"([0-9.]+)\"\\}", "{ZAR: \"$1\", USD: \"$1\"}");
        activate(both);
        var who = identity();
        assertThat(post(who, body("zar", "customer", "card", "0.10")).statusCode()).isEqualTo(201);
        var usd = post(who, body("usd", "customer", "card", "0.10").replace("ZAR", "USD"));
        assertThat(usd.statusCode()).as(usd.body()).isEqualTo(201);
        var evaluation = json.readTree(usd.body()).get("evaluation");
        assertThat(fact(evaluation, "TRANSACTION_VELOCITY", "count").asLong()).isEqualTo(2);
        assertThat(status(evaluation, "TRANSACTION_VELOCITY")).isEqualTo("MATCH");
        assertThat(fact(evaluation, "CUMULATIVE_SPEND", "total").asDecimal())
                .isEqualByComparingTo("0.10");
        assertThat(status(evaluation, "CUMULATIVE_SPEND")).isEqualTo("NO_MATCH");
        var zar = post(who, body("zar-again", "customer", "card", "0.10"));
        assertThat(zar.statusCode()).as(zar.body()).isEqualTo(201);
        assertThat(
                        fact(
                                        json.readTree(zar.body()).get("evaluation"),
                                        "CUMULATIVE_SPEND",
                                        "total")
                                .asDecimal())
                .isEqualByComparingTo("0.20");
    }

    @Test
    void aHistoryTimeoutRollsBackTheCurrentEventAndRetryHasNoPhantomHistory() throws Exception {
        for (boolean failCount : List.of(true, false)) {
            var who = identity();
            String input = body("retry", "customer", "card", "0.10");
            doAnswer(
                            invocation -> {
                                HistoryQuery query = invocation.getArgument(2);
                                if ((query instanceof HistoryQuery.Count) == failCount)
                                    throw new QueryTimeoutException(
                                            "Synthetic history query timeout");
                                return invocation.callRealMethod();
                            })
                    .when(store)
                    .aggregate(anyString(), anyString(), any(), any(), anyLong());
            var failed = post(who, input);
            assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
            assertThat(json.readTree(failed.body()).get("outcome").asString())
                    .isEqualTo("NOT_ACCEPTED");
            assertCounts(who, 0);
            assertThat(
                            database("fraud_runtime")
                                    .queryForObject(
                                            "SELECT count(*) FROM fraud.customer_processing_state WHERE scope=?",
                                            Integer.class,
                                            who.scope()))
                    .isZero();
            reset(store);
            var retry = post(who, input);
            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(201);
            assertThat(
                            fact(
                                            json.readTree(retry.body()).get("evaluation"),
                                            "TRANSACTION_VELOCITY",
                                            "count")
                                    .asLong())
                    .isEqualTo(1);
            assertCounts(who, 1);
        }
    }

    @Test
    void concurrentDifferentEventsSerializeIntoOneConsistentCustomerHistory() throws Exception {
        activate(
                original.replace("maxTransactions: 5", "maxTransactions: 2")
                        .replace("30000.00", "0.30"));
        var who = identity();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var jobs = new ArrayList<Future<HttpResponse<String>>>();
            for (int n = 0; n < 6; n++) {
                String input = body("parallel-" + n, "customer", "card", "0.10");
                jobs.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return post(who, input);
                                }));
            }
            start.countDown();
            var watermarks = new TreeSet<Long>();
            for (var job : jobs) {
                var response = job.get(12, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                var evaluation = json.readTree(response.body()).get("evaluation");
                long watermark = evaluation.get("contextHighWatermark").asLong();
                watermarks.add(watermark);
                assertThat(fact(evaluation, "TRANSACTION_VELOCITY", "count").asLong())
                        .isEqualTo(watermark + 1);
                assertThat(fact(evaluation, "CUMULATIVE_SPEND", "total").asDecimal())
                        .isEqualByComparingTo(java.math.BigDecimal.valueOf(watermark + 1, 1));
            }
            assertThat(watermarks).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
        }
        assertCounts(who, 6);
    }

    @Test
    void delayedAndFlaggedPaymentsEnterCustomerHistoryAcrossInstruments() throws Exception {
        var who = identity();
        String old =
                body(
                        "flagged",
                        "customer",
                        "first-card",
                        "30000.01",
                        Instant.now().minusSeconds(3600));
        var first = post(who, old);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(json.readTree(first.body()).at("/evaluation/decision").asString())
                .isEqualTo("FLAGGED");
        var next = post(who, body("next", "customer", "second-card", "0.01"));
        assertThat(next.statusCode()).as(next.body()).isEqualTo(201);
        var evaluation = json.readTree(next.body()).get("evaluation");
        assertThat(fact(evaluation, "TRANSACTION_VELOCITY", "count").asLong()).isEqualTo(2);
        assertThat(fact(evaluation, "CUMULATIVE_SPEND", "total").asDecimal())
                .isEqualByComparingTo("30000.02");
    }

    @Test
    void activationDuringHistoryLoadingCannotMixPolicyWindowsOrThresholds() throws Exception {
        var who = identity();
        var clock = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MICROS));
        doAnswer(invocation -> clock.get()).when(store).databaseTime();
        String narrow =
                original.replace("windowSeconds: 600", "windowSeconds: 5")
                        .replace("windowSeconds: 1800", "windowSeconds: 5")
                        .replace("30000.00", "0.15");
        String oldHash = activate(narrow);
        assertThat(post(who, body("before", "customer", "card", "0.10")).statusCode())
                .isEqualTo(201);
        clock.set(clock.get().plusSeconds(10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            if (once.compareAndSet(false, true)) {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IllegalStateException("Test release timed out");
                            }
                            return invocation.callRealMethod();
                        })
                .when(store)
                .aggregate(anyString(), anyString(), any(), any(), anyLong());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending =
                    executor.submit(() -> post(who, body("captured", "customer", "card", "0.10")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                String newHash = activate(narrow.replace("windowSeconds: 5", "windowSeconds: 20"));
                release.countDown();
                var response = pending.get(12, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                var captured = json.readTree(response.body()).get("evaluation");
                assertThat(captured.get("policyHash").asString()).isEqualTo(oldHash);
                assertThat(fact(captured, "CUMULATIVE_SPEND", "total").asDecimal())
                        .isEqualByComparingTo("0.10");
                var after = post(who, body("after", "customer", "card", "0.10"));
                var evaluation = json.readTree(after.body()).get("evaluation");
                assertThat(evaluation.get("policyHash").asString()).isEqualTo(newHash);
                assertThat(fact(evaluation, "CUMULATIVE_SPEND", "total").asDecimal())
                        .isEqualByComparingTo("0.30");
            } finally {
                release.countDown();
            }
        }
    }

    private String activate(String source) {
        var snapshot =
                new PolicyLoader(
                                new StrictYaml(),
                                RuleRegistry.of(
                                        BuiltInRules.definitions(),
                                        limits.api().maxRegisteredRules()))
                        .read(source);
        new RuleEngine(
                        List.of(
                                new HighAmountRule(),
                                new TransactionVelocityRule(),
                                new CumulativeSpendRule(),
                                new SmallPaymentBurstRule(),
                                new ImpossibleTravelRule(
                                        limits.api().maxLocationCandidatesPerEvaluation())),
                        limits.api().maxEvidenceFactsPerRule())
                .verify(snapshot);
        return database("fraud_policy_admin")
                .queryForObject(
                        "SELECT fraud.activate_policy(?,?,false)",
                        String.class,
                        snapshot.policyHash(),
                        source);
    }

    private JsonNode result(JsonNode evaluation, String id) {
        for (var r : evaluation.get("ruleResults"))
            if (r.get("ruleId").asString().equals(id)) return r;
        throw new AssertionError("Missing " + id);
    }

    private JsonNode fact(JsonNode evaluation, String rule, String field) {
        return result(evaluation, rule).get("evidence").get(field);
    }

    private String status(JsonNode evaluation, String rule) {
        return result(evaluation, rule).get("status").asString();
    }

    private void assertCounts(Identity who, int events) {
        for (String table : List.of("transactions", "evaluations"))
            assertThat(
                            database("fraud_runtime")
                                    .queryForObject(
                                            "SELECT count(*) FROM fraud."
                                                    + table
                                                    + " WHERE scope=?",
                                            Integer.class,
                                            who.scope()))
                    .isEqualTo(events);
        assertThat(
                        database("fraud_runtime")
                                .queryForObject(
                                        "SELECT count(*) FROM fraud.rule_results WHERE scope=?",
                                        Integer.class,
                                        who.scope()))
                .isEqualTo(events * 5);
    }

    private String body(String event, String customer, String card, String amount) {
        return body(event, customer, card, amount, Instant.now());
    }

    private String body(
            String event, String customer, String card, String amount, Instant occurredAt) {
        return json.writeValueAsString(
                Map.of(
                        "eventId",
                        event,
                        "customerId",
                        customer,
                        "instrumentId",
                        card,
                        "transactionType",
                        "CARD_PURCHASE",
                        "status",
                        "BOOKED",
                        "channel",
                        "ECOMMERCE",
                        "amount",
                        amount,
                        "currency",
                        "ZAR",
                        "category",
                        "GROCERIES",
                        "occurredAt",
                        occurredAt.truncatedTo(ChronoUnit.MICROS).toString()));
    }

    private HttpResponse<String> post(Identity who, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/api/v1/transactions"))
                        .timeout(Duration.ofMillis(limits.api().exampleClientDeadlineMillis()))
                        .header("X-API-Key", who.key())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
