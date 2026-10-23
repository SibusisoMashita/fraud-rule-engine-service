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
class SmallPaymentBurstEvaluationTest {
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
        var who = new Identity("sequence-http-" + id, id);
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
    void threeSmallPaymentsAcrossCardsThenALargePaymentAreExplainableAndReplayable()
            throws Exception {
        var who = identity();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seed(who, now, 3, "50.00");
        String input = body("large", "customer", "large-card", "5000.01", now);
        var created = post(who, input);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var evaluation = json.readTree(created.body()).get("evaluation");
        assertThat(evaluation.get("decision").asString()).isEqualTo("FLAGGED");
        assertThat(evaluation.get("coverage").asString()).isEqualTo("COMPLETE");
        assertThat(burst(created).get("status").asString()).isEqualTo("MATCH");
        assertThat(burst(created).at("/evidence/count").asLong()).isEqualTo(3);
        assertThat(burst(created).at("/evidence/total").asDecimal()).isEqualByComparingTo("150.00");
        assertThat(burst(created).at("/evidence/contextHighWatermark").asLong()).isEqualTo(3);
        var replay = post(who, input);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(created.body());
        var get =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/transactions/large"))
                                .header("X-API-Key", who.key())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(created.body());
        assertCounts(who, 4);
    }

    @Test
    void equalLargeThresholdSkipsHistoryAndDoesNotInventCountEvidence() throws Exception {
        var who = identity();
        Instant now = Instant.now();
        seed(who, now, 3, "50.00");
        clearInvocations(store);
        var response = post(who, body("equal", "customer", "card", "5000.00", now));
        assertThat(burst(response).get("reasonCode").asString()).isEqualTo("AMOUNT_NOT_LARGE");
        assertThat(burst(response).get("evidence").has("count")).isFalse();
        verify(store, never()).smallPayments(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void delayedLargePaymentsExcludeLaterAndEqualTimesAndReplayNeverIncorporatesLaterArrivals()
            throws Exception {
        var who = identity();
        Instant time = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        for (int n = 0; n < 3; n++) {
            assertThat(
                            post(
                                            who,
                                            body(
                                                    "later-" + n,
                                                    "customer",
                                                    "card",
                                                    "1.00",
                                                    time.plusSeconds(1)))
                                    .statusCode())
                    .isEqualTo(201);
            assertThat(post(who, body("equal-" + n, "customer", "card", "1.00", time)).statusCode())
                    .isEqualTo(201);
        }
        String input = body("delayed-large", "customer", "card", "5000.01", time);
        var before = post(who, input);
        assertThat(burst(before).at("/evidence/count").asLong()).isZero();
        assertThat(burst(before).get("status").asString()).isEqualTo("NO_MATCH");
        seed(who, time, 3, "1.00");
        var replay = post(who, input);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(before.body());
        var after = post(who, body("new-large", "customer", "card", "5000.01", time));
        assertThat(burst(after).at("/evidence/count").asLong()).isEqualTo(3);
        assertThat(burst(after).get("status").asString()).isEqualTo("MATCH");
    }

    @Test
    void allBusinessSettingsAndToggleChangeLiveWhileOriginalEvaluationRemainsImmutable()
            throws Exception {
        var who = identity();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seed(who, now, 3, "50.00");
        String input = body("original-large", "customer", "card", "5000.01", now);
        var originalResult = post(who, input);
        assertThat(burst(originalResult).get("status").asString()).isEqualTo("MATCH");
        var changes = new LinkedHashMap<String, String>();
        changes.put("count", original.replace("minSmallPayments: 3", "minSmallPayments: 4"));
        changes.put(
                "small",
                original.replace(
                        "smallPaymentMaximum: {ZAR: \"50.00\"}",
                        "smallPaymentMaximum: {ZAR: \"49.99\"}"));
        changes.put("window", original.replace("windowSeconds: 900", "windowSeconds: 2"));
        changes.put(
                "large",
                original.replace(
                        "largePaymentThreshold: {ZAR: \"5000.00\"}",
                        "largePaymentThreshold: {ZAR: \"6000.00\"}"));
        for (var change : changes.entrySet()) {
            String hash = activate(change.getValue());
            var response = post(who, body(change.getKey(), "customer", "card", "5000.01", now));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(burst(response).get("status").asString()).isEqualTo("NO_MATCH");
            assertThat(json.readTree(response.body()).at("/evaluation/policyHash").asString())
                    .isEqualTo(hash);
        }
        activate(
                original.replace(
                        "smallPaymentBurstThenLarge:\n    enabled: true",
                        "smallPaymentBurstThenLarge:\n    enabled: false"));
        clearInvocations(store);
        var disabled = post(who, body("disabled", "customer", "card", "5000.01", now));
        assertThat(burst(disabled).get("status").asString()).isEqualTo("DISABLED");
        verify(store, never()).smallPayments(anyString(), anyString(), any(), anyLong());
        activate(original);
        assertThat(post(who, input).body()).isEqualTo(originalResult.body());
    }

    @Test
    void currencyCustomerScopeAndPreviouslyFlaggedHistoryAreHandledExplicitly() throws Exception {
        String both =
                original.replace(
                                "  ZAR: {scale: 2, maxAmount: \"999999999999.99\"}",
                                "  ZAR: {scale: 2, maxAmount: \"999999999999.99\"}\n  USD: {scale: 2, maxAmount: \"999999999999.99\"}")
                        .replace("25000.00", "0.01")
                        .replaceAll("\\{ZAR: \"([0-9.]+)\"\\}", "{ZAR: \"$1\", USD: \"$1\"}");
        activate(both);
        var who = identity();
        Instant now = Instant.now();
        seed(who, now, 3, "0.10");
        assertThat(
                        database("fraud_runtime")
                                .queryForObject(
                                        "SELECT count(*) FROM fraud.evaluations WHERE scope=? AND decision='FLAGGED'",
                                        Integer.class,
                                        who.scope()))
                .isEqualTo(3);
        var zar = post(who, body("zar-large", "customer", "card", "5000.01", now));
        assertThat(burst(zar).get("status").asString()).isEqualTo("MATCH");
        var usd =
                post(
                        who,
                        body("usd-large", "customer", "card", "5000.01", now)
                                .replace("ZAR", "USD"));
        assertThat(burst(usd).at("/evidence/count").asLong()).isZero();
        var otherCustomer =
                post(who, body("other-customer", "other-customer", "other-card", "5000.01", now));
        assertThat(burst(otherCustomer).at("/evidence/count").asLong()).isZero();
        var otherScope = post(identity(), body("other-scope", "customer", "card", "5000.01", now));
        assertThat(burst(otherScope).at("/evidence/count").asLong()).isZero();
    }

    @Test
    void queryFailureRollsBackAllCurrentStateAndRetryUsesOnlyCommittedPayments() throws Exception {
        var who = identity();
        Instant now = Instant.now();
        seed(who, now, 3, "1.00");
        String input = body("retry", "customer", "card", "5000.01", now);
        doThrow(new QueryTimeoutException("Synthetic small-payment timeout"))
                .when(store)
                .smallPayments(anyString(), anyString(), any(), anyLong());
        var failed = post(who, input);
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
        assertThat(json.readTree(failed.body()).get("outcome").asString())
                .isEqualTo("NOT_ACCEPTED");
        assertCounts(who, 3);
        assertThat(
                        database("fraud_runtime")
                                .queryForObject(
                                        "SELECT customer_sequence FROM fraud.customer_processing_state WHERE scope=?",
                                        Long.class,
                                        who.scope()))
                .isEqualTo(3);
        reset(store);
        var retry = post(who, input);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(burst(retry).at("/evidence/count").asLong()).isEqualTo(3);
        assertCounts(who, 4);
    }

    @Test
    void activationDuringTheQueryCannotMixPolicyWithCapturedHistoryOrEvidence() throws Exception {
        var who = identity();
        Instant now = Instant.now();
        seed(who, now, 3, "50.00");
        String old = activate(original);
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
                .smallPayments(anyString(), anyString(), any(), anyLong());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending =
                    executor.submit(
                            () -> post(who, body("captured", "customer", "card", "5000.01", now)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                String next =
                        activate(original.replace("minSmallPayments: 3", "minSmallPayments: 4"));
                release.countDown();
                var captured = pending.get(12, TimeUnit.SECONDS);
                assertThat(captured.statusCode()).as(captured.body()).isEqualTo(201);
                assertThat(burst(captured).get("status").asString()).isEqualTo("MATCH");
                assertThat(json.readTree(captured.body()).at("/evaluation/policyHash").asString())
                        .isEqualTo(old);
                var after = post(who, body("after", "customer", "card", "5000.01", now));
                assertThat(burst(after).get("status").asString()).isEqualTo("NO_MATCH");
                assertThat(json.readTree(after.body()).at("/evaluation/policyHash").asString())
                        .isEqualTo(next);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void concurrentSmallAndLargeArrivalsUseOneConsistentCommittedWatermark() throws Exception {
        var who = identity();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seed(who, now, 2, "1.00");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var small =
                    executor.submit(
                            () -> {
                                start.await();
                                return post(
                                        who,
                                        body(
                                                "third-small",
                                                "customer",
                                                "card",
                                                "1.00",
                                                now.minusSeconds(1)));
                            });
            var large =
                    executor.submit(
                            () -> {
                                start.await();
                                return post(
                                        who,
                                        body("racing-large", "customer", "card", "5000.01", now));
                            });
            start.countDown();
            assertThat(small.get(12, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            var result = large.get(12, TimeUnit.SECONDS);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
            long watermark =
                    json.readTree(result.body()).at("/evaluation/contextHighWatermark").asLong();
            assertThat(watermark).isIn(2L, 3L);
            assertThat(burst(result).at("/evidence/count").asLong()).isEqualTo(watermark);
            assertThat(burst(result).get("status").asString())
                    .isEqualTo(watermark == 3 ? "MATCH" : "NO_MATCH");
        }
        assertCounts(who, 4);
    }

    private void seed(Identity who, Instant time, int count, String amount) throws Exception {
        for (int n = 0; n < count; n++)
            assertThat(
                            post(
                                            who,
                                            body(
                                                    "small-" + n,
                                                    "customer",
                                                    "small-card-" + n,
                                                    amount,
                                                    time.minusSeconds(30 + n)))
                                    .statusCode())
                    .isEqualTo(201);
    }

    private JsonNode burst(HttpResponse<String> response) {
        return result(
                json.readTree(response.body()).get("evaluation"), "SMALL_PAYMENT_BURST_THEN_LARGE");
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
