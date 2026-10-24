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
import tools.jackson.databind.node.ObjectNode;

import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.evaluation.FlowFailure;
import za.co.sibusiso.fraud.evaluation.application.EvaluationQuery;
import za.co.sibusiso.fraud.evaluation.application.EvaluationQueryService;
import za.co.sibusiso.fraud.evaluation.persistence.JdbcEvaluationQueryStore;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvaluationQueryTest {
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
    @Autowired EvaluationQueryService queries;
    @Autowired JdbcTemplate runtimeJdbc;
    @MockitoSpyBean JdbcEvaluationQueryStore queryStore;
    @MockitoSpyBean za.co.sibusiso.fraud.policy.JdbcActivePolicyStore activeStore;
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
        var who = new Identity("query-http-" + id, id);
        var admin = database("fraud_security_admin");
        admin.update(
                "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,ARRAY['INGEST','READ','POLICY'],ARRAY[]::text[])",
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
        reset(store, queryStore, activeStore);
        activate(original);
    }

    @Test
    void tiedTimestampsPaginateWithoutDuplicatesAndReturnOriginalEvaluationDocuments()
            throws Exception {
        var who = identity();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock(time);
        var originals = new HashMap<String, JsonNode>();
        for (int n = 0; n < 7; n++) {
            var response = post(who, body("event-" + n, "customer", "card", "1.00", time));
            assertThat(response.statusCode()).isEqualTo(201);
            var evaluation = json.readTree(response.body()).get("evaluation");
            originals.put(evaluation.get("id").asString(), evaluation);
        }
        var params = range(time.minusSeconds(1), time.plusSeconds(1), 2);
        var ids = new ArrayList<String>();
        String cursor = null;
        do {
            if (cursor != null) params.put("cursor", cursor);
            var response = page(who, params);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var result = json.readTree(response.body());
            assertThat(result.get("items").size()).isBetween(1, 2);
            for (var item : result.get("items")) {
                String id = item.get("id").asString();
                ids.add(id);
                assertThat(item).isEqualTo(originals.get(id));
            }
            cursor = result.has("nextCursor") ? result.get("nextCursor").asString() : null;
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("set-cookie")).isEmpty();
        } while (cursor != null);
        assertThat(ids).hasSize(7).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void paginatedEvaluationsPreserveExactDecimalsFromTheOriginalResponse() throws Exception {
        var who = identity();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock(time.minusSeconds(1));
        database("fraud_security_admin")
                .update(
                        "UPDATE fraud.api_principals SET location_sources=ARRAY['VERIFIED_CARD_PRESENT_TERMINAL']::text[] WHERE scope=?",
                        who.scope());
        var referenceAccuracy = new BigDecimal("0.98765432109876543210987654321");
        var currentAccuracy = new BigDecimal("0.12345678901234567890123456789");
        var latitude = new BigDecimal("-26.204100000000000000000000001");
        var longitude = new BigDecimal("28.047300000000000000000000001");
        var reference =
                (ObjectNode)
                        json.readTree(
                                body(
                                        "precise-reference",
                                        "customer",
                                        "card",
                                        "1.00",
                                        time.minusSeconds(600)));
        reference.put("channel", "CARD_PRESENT");
        reference.set(
                "physicalLocation",
                json.valueToTree(
                        Map.of(
                                "latitude",
                                new BigDecimal("-33.9249"),
                                "longitude",
                                new BigDecimal("18.4241"),
                                "accuracyKm",
                                referenceAccuracy,
                                "source",
                                "VERIFIED_CARD_PRESENT_TERMINAL")));
        var first = post(who, json.writeValueAsString(reference));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        clock(time);
        var current =
                (ObjectNode)
                        json.readTree(body("precise-current", "customer", "card", "1.00", time));
        current.put("channel", "CARD_PRESENT");
        current.set(
                "physicalLocation",
                json.valueToTree(
                        Map.of(
                                "latitude",
                                latitude,
                                "longitude",
                                longitude,
                                "accuracyKm",
                                currentAccuracy,
                                "source",
                                "VERIFIED_CARD_PRESENT_TERMINAL")));
        var submitted = post(who, json.writeValueAsString(current));
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        var retrieved = get(who, "/api/v1/transactions/precise-current");
        assertThat(retrieved.statusCode()).as(retrieved.body()).isEqualTo(200);
        for (String document : List.of(submitted.body(), retrieved.body())) {
            assertThat(numberTokens(document, "latitude")).containsExactly(latitude);
            assertThat(numberTokens(document, "longitude")).containsExactly(longitude);
            assertThat(numberTokens(document, "currentAccuracyKm"))
                    .containsExactly(currentAccuracy);
            assertThat(numberTokens(document, "referenceAccuracyKm"))
                    .containsExactly(referenceAccuracy);
        }
        var parameters = range(time.minusSeconds(2), time.plusSeconds(1), 1);
        var firstPage = page(who, parameters);
        assertThat(eventIds(firstPage)).containsExactly("precise-reference");
        parameters.put("cursor", json.readTree(firstPage.body()).get("nextCursor").asString());
        var nextPage = page(who, parameters);
        assertThat(eventIds(nextPage)).containsExactly("precise-current");
        assertThat(numberTokens(nextPage.body(), "currentAccuracyKm"))
                .containsExactly(currentAccuracy);
        assertThat(numberTokens(nextPage.body(), "referenceAccuracyKm"))
                .containsExactly(referenceAccuracy);
        assertThat(json.readTree(nextPage.body()).has("nextCursor")).isFalse();
    }

    @Test
    void halfOpenDatesFiltersAndScopeIsolationApplyBeforePaging() throws Exception {
        var who = identity();
        var other = identity();
        Instant time = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        clock(time.minusNanos(1000));
        create(who, "old", "old-customer", "1.00", time);
        clock(time);
        create(who, "lower", "target", "1.00", time);
        clock(time.plusSeconds(1).minusNanos(1000));
        create(who, "flagged", "target", "25000.01", time);
        clock(time.plusSeconds(1));
        create(who, "upper", "upper-customer", "1.00", time);
        create(other, "foreign", "target", "1.00", time);
        var params = range(time, time.plusSeconds(1), 10);
        assertThat(eventIds(page(who, params))).containsExactly("lower", "flagged");
        params.put("customerId", "target");
        params.put("decision", "FLAGGED");
        assertThat(eventIds(page(who, params))).containsExactly("flagged");
        params.put("decision", "CLEAR");
        assertThat(eventIds(page(who, params))).containsExactly("lower");
        params.put("customerId", "missing");
        assertThat(eventIds(page(who, params))).isEmpty();
        assertThat(json.readTree(page(who, params).body()).has("nextCursor")).isFalse();
    }

    @Test
    void indeterminateFilterUsesStoredDecisionsInsideAReadOnlyTransaction() throws Exception {
        var who = identity();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock(time);
        var input =
                body("partial", "customer", "card", "1.00", time)
                        .replace("ECOMMERCE", "CARD_PRESENT");
        var response = post(who, input);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).at("/evaluation/decision").asString())
                .isEqualTo("INDETERMINATE");
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isCurrentTransactionReadOnly())
                                    .isTrue();
                            assertThat(
                                            runtimeJdbc.queryForObject(
                                                    "SHOW transaction_read_only", String.class))
                                    .isEqualTo("on");
                            return invocation.callRealMethod();
                        })
                .when(queryStore)
                .page(anyString(), any());
        var params = range(time.minusSeconds(1), time.plusSeconds(1), 10);
        params.put("decision", "INDETERMINATE");
        assertThat(eventIds(page(who, params))).containsExactly("partial");
        assertThat(
                        database("fraud_runtime")
                                .queryForObject(
                                        "SELECT count(*) FROM fraud.evaluations WHERE scope=?",
                                        Integer.class,
                                        who.scope()))
                .isEqualTo(1);
    }

    @Test
    void cursorsBindScopeFiltersDatesAndPageSizeAndRejectTampering() throws Exception {
        var who = identity();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock(time);
        for (int n = 0; n < 3; n++) create(who, "row-" + n, "customer", "1.00", time);
        var params = range(time.minusSeconds(1), time.plusSeconds(1), 1);
        params.put("customerId", "customer");
        var first = page(who, params);
        String token = json.readTree(first.body()).get("nextCursor").asString();
        params.put("cursor", token);
        assertThat(page(identity(), params).statusCode()).isEqualTo(400);
        for (var change :
                Map.of(
                                "customerId",
                                "other",
                                "decision",
                                "FLAGGED",
                                "pageSize",
                                "2",
                                "acceptedTo",
                                time.plusSeconds(2).toString())
                        .entrySet()) {
            var changed = new LinkedHashMap<>(params);
            changed.put(change.getKey(), change.getValue());
            assertThat(page(who, changed).statusCode()).isEqualTo(400);
        }
        var omitted = new LinkedHashMap<>(params);
        omitted.remove("acceptedFrom");
        assertThat(page(who, omitted).statusCode()).isEqualTo(400);
        params.put("cursor", token.substring(1));
        assertThat(page(who, params).statusCode()).isEqualTo(400);
    }

    @Test
    void defaultBoundsStayPinnedAndNewCommitsBeyondTheUpperBoundAreExcluded() throws Exception {
        var who = identity();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        clock(time.minusSeconds(2));
        create(who, "first", "customer", "1.00", time);
        clock(time.minusSeconds(1));
        create(who, "second", "customer", "1.00", time);
        doReturn(time).when(queryStore).now();
        var params = new LinkedHashMap<String, String>();
        params.put("pageSize", "1");
        var first = page(who, params);
        String token = json.readTree(first.body()).get("nextCursor").asString();
        clock(time.plusSeconds(1));
        create(who, "future", "customer", "1.00", time);
        clearInvocations(queryStore);
        params.put("cursor", token);
        var next = page(who, params);
        assertThat(eventIds(next)).containsExactly("second");
        assertThat(json.readTree(next.body()).has("nextCursor")).isFalse();
        verify(queryStore, never()).now();
    }

    @Test
    void lateCommitBehindACursorIsNotInventedIntoAReconciliationSnapshot() throws Exception {
        var who = identity();
        Instant time = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        // Hold a complete earlier evaluation uncommitted on an independent writer connection.
        try (var connection =
                java.sql.DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), "fraud_migrator", PASSWORD)) {
            connection.setAutoCommit(false);
            var pending =
                    new JdbcTemplate(
                            new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                                    connection, true));
            String policy =
                    database("fraud_runtime")
                            .queryForObject(
                                    "SELECT policy_hash FROM fraud.policy_head WHERE singleton",
                                    String.class);
            pending.update(
                    "INSERT INTO fraud.customer_processing_state(scope,customer_id,last_accepted_at,customer_sequence) VALUES (?,'late',?,1)",
                    who.scope(),
                    time.atOffset(ZoneOffset.UTC));
            pending.update(
                    "INSERT INTO fraud.instrument_owners(scope,instrument_id,customer_id) VALUES (?,'late','late')",
                    who.scope());
            pending.update(
                    "INSERT INTO fraud.transactions(scope,event_id,customer_id,instrument_id,request_hash,canonicalization_version,transaction_type,status,channel,amount,currency,category,occurred_at,accepted_at,customer_sequence,producer_id,source_permission_version,source_permissions,input_json) VALUES (?,'late','late','late',repeat('0',64),1,'CARD_PURCHASE','BOOKED','ECOMMERCE',1,'ZAR','SYNTHETIC',?,?,1,?,1,'[]','{}')",
                    who.scope(),
                    time.atOffset(ZoneOffset.UTC),
                    time.atOffset(ZoneOffset.UTC),
                    who.keyId());
            pending.update(
                    "INSERT INTO fraud.evaluations(scope,event_id,id,policy_hash,build_id,context_high_watermark,decision,coverage,evaluated_at,expected_rule_count) VALUES (?,'late',gen_random_uuid(),?,'synthetic-late-commit',0,'CLEAR','COMPLETE',?,1)",
                    who.scope(),
                    policy,
                    time.atOffset(ZoneOffset.UTC));
            pending.update(
                    "INSERT INTO fraud.rule_results(scope,event_id,rule_id,algorithm_version,status,reason_code,evidence) VALUES (?,'late','SYNTHETIC','1','NO_MATCH','SYNTHETIC_FIXTURE','{\"evidenceSchemaVersion\":1}')",
                    who.scope());
            clock(time.plusSeconds(1));
            create(who, "visible-1", "visible", "1.00", time);
            clock(time.plusSeconds(2));
            create(who, "visible-2", "visible", "1.00", time);
            var params = range(time.minusSeconds(1), time.plusSeconds(3), 1);
            var first = page(who, params);
            assertThat(eventIds(first)).containsExactly("visible-1");
            String cursor = json.readTree(first.body()).get("nextCursor").asString();
            connection.commit();
            params.put("cursor", cursor);
            assertThat(eventIds(page(who, params))).containsExactly("visible-2");
            params.remove("cursor");
            params.put("pageSize", "10");
            assertThat(eventIds(page(who, params)))
                    .containsExactly("late", "visible-1", "visible-2");
        }
    }

    @Test
    void malformedRangeAndQueryControlsAreRejectedWithoutDatabaseEnumeration() throws Exception {
        var who = identity();
        Instant time = Instant.now();
        for (String path :
                List.of(
                        "?scope=forged",
                        "?customerId=a&customerId=b",
                        "?pageSize=1.5",
                        "?decision=unknown",
                        "?offset=1"))
            assertThat(get(who, "/api/v1/evaluations" + path).statusCode()).isEqualTo(400);
        for (var params :
                List.of(
                        range(time, time, 1),
                        range(time, time.minusSeconds(1), 1),
                        range(time, time.plus(32, ChronoUnit.DAYS), 1)))
            assertThat(page(who, params).statusCode()).isEqualTo(422);
        assertThat(page(who, range(time.minus(31, ChronoUnit.DAYS), time, 1)).statusCode())
                .isEqualTo(200);
        assertThat(get(who, "/api/v1/evaluations?pageSize=101").statusCode()).isEqualTo(422);
        assertThat(get(who, "/api/v1/rules?scope=forged").statusCode()).isEqualTo(400);
    }

    @Test
    void readAndPolicyRolesRemainDistinctAndRevocationIsLive() throws Exception {
        var read = identity();
        var policy = identity();
        database("fraud_security_admin")
                .update(
                        "UPDATE fraud.api_principals SET roles=ARRAY['READ'] WHERE scope=?",
                        read.scope());
        database("fraud_security_admin")
                .update(
                        "UPDATE fraud.api_principals SET roles=ARRAY['POLICY'] WHERE scope=?",
                        policy.scope());
        assertThat(get(read, "/api/v1/evaluations").statusCode()).isEqualTo(200);
        assertThat(get(read, "/api/v1/rules").statusCode()).isEqualTo(403);
        assertThat(get(policy, "/api/v1/evaluations").statusCode()).isEqualTo(403);
        assertThat(get(policy, "/api/v1/rules").statusCode()).isEqualTo(200);
        database("fraud_security_admin")
                .update(
                        "UPDATE fraud.api_principals SET enabled=false WHERE scope=?",
                        policy.scope());
        assertThat(get(policy, "/api/v1/rules").statusCode()).isEqualTo(401);
    }

    @Test
    void policyInspectionReflectsLiveSettingsAndTogglesWithoutSecretOrInfrastructureData()
            throws Exception {
        var who = identity();
        var before = get(who, "/api/v1/rules");
        assertThat(before.statusCode()).isEqualTo(200);
        String changed =
                original.replace("minSmallPayments: 3", "minSmallPayments: 4")
                        .replace(
                                "smallPaymentBurstThenLarge:\n    enabled: true",
                                "smallPaymentBurstThenLarge:\n    enabled: false");
        String hash = activate(changed);
        var after = get(who, "/api/v1/rules");
        var document = json.readTree(after.body());
        assertThat(document.get("policyHash").asString()).isEqualTo(hash);
        assertThat(document.get("rules").size()).isEqualTo(5);
        var burst = result(document, "SMALL_PAYMENT_BURST_THEN_LARGE");
        assertThat(burst.get("enabled").asBoolean()).isFalse();
        assertThat(burst.at("/settings/minSmallPayments").asInt()).isEqualTo(4);
        assertThat(after.body())
                .doesNotContain(
                        SECRET, who.key(), "password", "dbPool", "cursor-signing", "policy_yaml");
        assertThat(after.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void inspectionCapturesOneWholeVersionDuringAnOverlappingActivation() throws Exception {
        var who = identity();
        String previous = activate(original);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            var reference = invocation.callRealMethod();
                            if (once.compareAndSet(false, true)) {
                                entered.countDown();
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IllegalStateException("Test release timed out");
                            }
                            return reference;
                        })
                .when(activeStore)
                .active();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> get(who, "/api/v1/rules"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                String next =
                        activate(original.replace("minSmallPayments: 3", "minSmallPayments: 4"));
                release.countDown();
                var captured = json.readTree(pending.get(12, TimeUnit.SECONDS).body());
                assertThat(captured.get("policyHash").asString()).isEqualTo(previous);
                assertThat(
                                result(captured, "SMALL_PAYMENT_BURST_THEN_LARGE")
                                        .at("/settings/minSmallPayments")
                                        .asInt())
                        .isEqualTo(3);
                assertThat(
                                json.readTree(get(who, "/api/v1/rules").body())
                                        .get("policyHash")
                                        .asString())
                        .isEqualTo(next);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedQueriesReturnSafeRetryableErrorsAndRecoverWithoutWritingFinancialState()
            throws Exception {
        var who = identity();
        doThrow(new QueryTimeoutException("Synthetic private query detail"))
                .when(queryStore)
                .page(anyString(), any());
        var failed = page(who, Map.of());
        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(503);
        assertThat(failed.body()).contains("NOT_ACCEPTED").doesNotContain("Synthetic", "SELECT");
        assertCounts(who, 0);
        reset(queryStore);
        assertThat(page(who, Map.of()).statusCode()).isEqualTo(200);
        var caller =
                new za.co.sibusiso.fraud.ingress.security.Caller(
                        who.keyId(),
                        who.scope(),
                        Set.of(za.co.sibusiso.fraud.ingress.security.Caller.Role.READ),
                        Set.of(),
                        1);
        assertThatThrownBy(
                        () ->
                                queries.list(
                                        caller,
                                        new EvaluationQuery.Request(
                                                null, null, null, null, null, null),
                                        () -> 0))
                .isInstanceOf(FlowFailure.class);
        String previous =
                database("fraud_runtime")
                        .queryForObject(
                                "SELECT policy_hash FROM fraud.policy_head WHERE singleton",
                                String.class);
        database("fraud_policy_admin")
                .queryForObject(
                        "SELECT fraud.activate_policy(?,?,false)",
                        String.class,
                        "0".repeat(64),
                        "schemaVersion: 999");
        assertThat(get(who, "/api/v1/rules").statusCode()).isEqualTo(503);
        activate(original);
        assertThat(json.readTree(get(who, "/api/v1/rules").body()).get("policyHash").asString())
                .isEqualTo(previous);
    }

    private List<BigDecimal> numberTokens(String document, String property) {
        var numbers = new ArrayList<BigDecimal>();
        // Read numeric tokens directly: a floating-point tree parser could hide the same rounding
        // on both endpoints.
        try (var parser = json.tokenStreamFactory().createParser(document)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken().isNumeric() && property.equals(parser.currentName()))
                    numbers.add(new BigDecimal(parser.getString()));
            }
        }
        return numbers;
    }

    private void clock(Instant value) {
        doReturn(value).when(store).databaseTime();
        doReturn(value).when(queryStore).now();
    }

    private void create(Identity who, String id, String customer, String amount, Instant occurred)
            throws Exception {
        var response = post(who, body(id, customer, customer, amount, occurred));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private LinkedHashMap<String, String> range(Instant from, Instant to, int size) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("acceptedFrom", from.truncatedTo(ChronoUnit.MICROS).toString());
        parameters.put("acceptedTo", to.truncatedTo(ChronoUnit.MICROS).toString());
        parameters.put("pageSize", Integer.toString(size));
        return parameters;
    }

    private HttpResponse<String> page(Identity who, Map<String, String> parameters)
            throws Exception {
        var query = new StringBuilder();
        parameters.forEach(
                (key, value) -> {
                    if (!query.isEmpty()) query.append('&');
                    query.append(key)
                            .append('=')
                            .append(
                                    java.net.URLEncoder.encode(
                                            value, java.nio.charset.StandardCharsets.UTF_8));
                });
        return get(who, "/api/v1/evaluations" + (query.isEmpty() ? "" : "?" + query));
    }

    private HttpResponse<String> get(Identity who, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(12))
                        .header("X-API-Key", who.key())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<String> eventIds(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var ids = new ArrayList<String>();
        for (var item : json.readTree(response.body()).get("items"))
            ids.add(item.get("eventId").asString());
        return ids;
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
        for (var r :
                (evaluation.has("rules") ? evaluation.get("rules") : evaluation.get("ruleResults")))
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
