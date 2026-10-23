package za.co.sibusiso.fraud;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.ingress.ApiPaths;
import za.co.sibusiso.fraud.ingress.security.CredentialAuthenticator;
import za.co.sibusiso.fraud.ingress.security.IngressRoute;
import za.co.sibusiso.fraud.ingress.security.SharedRateLimiter;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import javax.sql.DataSource;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyManagementApiTest {
    private static final String PASSWORD = TestDatabase.PASSWORD;
    private static final String SECRET = "e".repeat(64);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final StrictYaml YAML = new StrictYaml();
    private static final String ORIGINAL =
            YAML.readText(new ClassPathResource("config/policy.example.yaml"));

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
        properties.add("spring.datasource.hikari.auto-commit", () -> "false");
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password", () -> PASSWORD);
        properties.add("fraud.policy-management.enabled", () -> "true");
        properties.add("fraud.policy-management.max-connections", () -> "1");
        properties.add("fraud.policy-management.password", () -> PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate runtime;
    @Autowired ConfigurableApplicationContext context;
    @Autowired CredentialAuthenticator authenticator;
    @Autowired StringRedisTemplate redis;
    @Autowired PolicyLoader loader;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Identity operator;
    private Identity producer;

    @BeforeEach
    void callers() {
        operator = identity("{POLICY_ADMIN}");
        producer = identity("{INGEST,READ,POLICY}");
    }

    @AfterEach
    void restoreOriginalPolicy() {
        activateFromCli(ORIGINAL);
    }

    @Test
    void currentPolicyIsCompleteEditableJsonWithExactMoneyAndARevisionEtag() throws Exception {
        var response = current(operator);
        var active = JSON.readTree(response.body());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("ETag"))
                .contains("\"policy-revision-" + active.path("revision").asLong() + "\"");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(active.path("document")).isEqualTo(JSON.readTree(sample()));
        assertThat(active.at("/document/supportedCurrencies/ZAR/maxAmount").isString()).isTrue();
        assertThat(active.at("/document/supportedCurrencies/ZAR/maxAmount").asString())
                .isEqualTo("999999999999.99");
        assertThat(active.at("/document/rules/highAmount/thresholds/ZAR").isString()).isTrue();
        assertThat(active.at("/document/rules/highAmount/thresholds/ZAR").asString())
                .isEqualTo("25000.00");
        var document = YAML.convert(active.path("document"), PolicyLoader.Document.class);
        assertThat(loader.read(document).policyHash())
                .isEqualTo(active.path("policyHash").asString());
        assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        assertThat(context.getBean(DataSource.class))
                .isInstanceOfSatisfying(
                        HikariDataSource.class, pool -> assertThat(pool.isAutoCommit()).isFalse());
        assertThat(context.getBeansOfType(JdbcTemplate.class)).hasSize(1);
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(runtime.queryForObject("SELECT current_user", String.class))
                .isEqualTo("fraud_runtime");
    }

    @Test
    void validationReturnsTheCandidateHashWithoutChangingVersionsHeadOrAudit() throws Exception {
        var before = state();
        var candidate = sample().replace("25000.00", "34000.01");
        var response =
                call("POST", ApiPaths.ADMIN_POLICY_VALIDATE, operator.key(), candidate, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var validated = JSON.readTree(response.body());
        assertThat(validated.path("policyHash").asString())
                .isEqualTo(loader.read(candidate).policyHash());
        assertThat(validated.path("policyHash").asString()).isNotEqualTo(before.hash());
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPolicies")
    void rejectedDocumentsCannotWriteThroughValidationOrActivation(
            String description, String candidate, int status, String code) throws Exception {
        var before = state();
        for (var route :
                new String[][] {
                    {"POST", ApiPaths.ADMIN_POLICY_VALIDATE}, {"PUT", ApiPaths.ADMIN_POLICY}
                }) {
            assertProblem(
                    call(route[0], route[1], operator.key(), candidate, etag(before.revision())),
                    status,
                    code);
            assertThat(state()).as(description).isEqualTo(before);
        }
    }

    static Stream<Arguments> invalidPolicies() {
        var unknown = (ObjectNode) JSON.readTree(sample());
        unknown.put("unexpected", true);
        var disabled = (ObjectNode) JSON.readTree(sample());
        for (var settings : disabled.path("rules")) ((ObjectNode) settings).put("enabled", false);
        String duplicate =
                sample().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
        return Stream.of(
                Arguments.of(
                        "unknown policy field",
                        JSON.writeValueAsString(unknown),
                        422,
                        "POLICY_INVALID"),
                Arguments.of("duplicate JSON field", duplicate, 400, "INVALID_JSON"),
                Arguments.of(
                        "all rules disabled",
                        JSON.writeValueAsString(disabled),
                        422,
                        "POLICY_INVALID"));
    }

    @Test
    void activationRequiresAnExplicitRevisionPrecondition() throws Exception {
        var before = state();
        assertProblem(
                call("PUT", ApiPaths.ADMIN_POLICY, operator.key(), sample(), null),
                428,
                "POLICY_PRECONDITION_REQUIRED");
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*",
                "W/\"policy-revision-1\"",
                "\"policy-revision-0\"",
                "\"policy-revision-1\", \"policy-revision-2\"",
                "\"policy-revision-9223372036854775808\""
            })
    void malformedOrNonSpecificRevisionHeadersCannotActivate(String header) throws Exception {
        var before = state();
        assertProblem(
                call("PUT", ApiPaths.ADMIN_POLICY, operator.key(), sample(), header),
                400,
                "INVALID_REQUEST");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void cliActivationMakesAnOlderBrowserRevisionFailWithoutOverwritingIt() throws Exception {
        var browser = current(operator);
        activateFromCli(ORIGINAL.replace("25000.00", "36000.00"));
        var afterCli = state();
        assertProblem(
                call(
                        "PUT",
                        ApiPaths.ADMIN_POLICY,
                        operator.key(),
                        sample().replace("25000.00", "37000.00"),
                        browser.headers().firstValue("ETag").orElseThrow()),
                412,
                "POLICY_REVISION_CONFLICT");
        assertThat(state()).isEqualTo(afterCli);
        assertThat(JSON.readTree(current(operator).body()).path("policyHash").asString())
                .isEqualTo(afterCli.hash());
    }

    @Test
    void equivalentPolicyKeepsTheRevisionAuditAndOriginalStoredRepresentation() throws Exception {
        var current = current(operator);
        var before = state();
        String tag = current.headers().firstValue("ETag").orElseThrow();
        var identical = call("PUT", ApiPaths.ADMIN_POLICY, operator.key(), sample(), tag);
        var equivalent =
                call(
                        "PUT",
                        ApiPaths.ADMIN_POLICY,
                        operator.key(),
                        sample().replace("25000.00", "25000"),
                        tag);
        assertThat(identical.statusCode()).as(identical.body()).isEqualTo(200);
        assertThat(equivalent.statusCode()).as(equivalent.body()).isEqualTo(200);
        assertThat(equivalent.headers().firstValue("ETag")).contains(tag);
        assertThat(JSON.readTree(equivalent.body())).isEqualTo(JSON.readTree(current.body()));
        assertThat(JSON.readTree(current(operator).body()))
                .isEqualTo(JSON.readTree(current.body()));
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void reactivatingAnExistingVersionCommitsBeforeReturningSuccess() throws Exception {
        String originalHash = loader.read(ORIGINAL).policyHash();
        activateFromCli(ORIGINAL.replace("25000.00", "45000.01"));
        var before = state();
        assertThat(before.hash()).isNotEqualTo(originalHash);

        var response =
                call(
                        "PUT",
                        ApiPaths.ADMIN_POLICY,
                        operator.key(),
                        sample(),
                        etag(before.revision()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var active = JSON.readTree(response.body());
        assertThat(active.path("policyHash").asString()).isEqualTo(originalHash);
        assertThat(active.path("revision").asLong()).isEqualTo(before.revision() + 1);
        var committed = state();
        assertThat(committed.revision())
                .as("The HTTP 200 activation revision must already be committed")
                .isEqualTo(active.path("revision").asLong());
        assertThat(committed.hash()).isEqualTo(originalHash);
        assertThat(committed.versions()).isEqualTo(before.versions());
        assertThat(committed.activations()).isEqualTo(before.activations() + 1);
        assertThat(
                        runtime.queryForObject(
                                "SELECT activated_by FROM fraud.policy_activations WHERE revision=?",
                                String.class,
                                committed.revision()))
                .isEqualTo("fraud_policy_api");
        assertThat(
                        runtime.queryForObject(
                                "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?",
                                String.class,
                                committed.revision()))
                .isEqualTo(operator.principal());
        var fresh = current(operator);
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(200);
        assertThat(fresh.headers().firstValue("ETag"))
                .isEqualTo(response.headers().firstValue("ETag"));
        assertThat(JSON.readTree(fresh.body())).isEqualTo(active);
    }

    @Test
    void globalActivationChangesNewTransactionsAcrossScopesWithoutRewritingSavedResults()
            throws Exception {
        long startup = context.getStartupDate();
        var otherProducer = identity("{INGEST,READ}");
        String firstBody = transaction("before", "customer-before", "card-before");
        var first = call("POST", ApiPaths.TRANSACTIONS, producer.key(), firstBody, null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(JSON.readTree(first.body()).at("/evaluation/decision").asString())
                .isEqualTo("FLAGGED");
        var current = current(operator);
        var before = state();
        var activationRequest =
                request(
                                "PUT",
                                ApiPaths.ADMIN_POLICY,
                                operator.key(),
                                sample().replace("25000.00", "35000.01"),
                                current.headers().firstValue("ETag").orElseThrow())
                        .header("X-Principal-ID", "forged-operator");
        var activated = http.send(activationRequest.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        var active = JSON.readTree(activated.body());
        assertThat(active.path("revision").asLong()).isEqualTo(before.revision() + 1);
        assertThat(state().versions()).isEqualTo(before.versions() + 1);
        assertThat(state().activations()).isEqualTo(before.activations() + 1);
        assertThat(
                        runtime.queryForObject(
                                "SELECT activated_by FROM fraud.policy_activations WHERE revision=?",
                                String.class,
                                active.path("revision").asLong()))
                .isEqualTo("fraud_policy_api");
        assertThat(
                        runtime.queryForObject(
                                "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?",
                                String.class,
                                active.path("revision").asLong()))
                .isEqualTo(operator.principal());
        for (var identity : new Identity[] {producer, otherProducer}) {
            var after =
                    call(
                            "POST",
                            ApiPaths.TRANSACTIONS,
                            identity.key(),
                            transaction("after", "customer-after", "card-after"),
                            null);
            assertThat(after.statusCode()).as(after.body()).isEqualTo(201);
            assertThat(JSON.readTree(after.body()).at("/evaluation/decision").asString())
                    .isEqualTo("CLEAR");
            assertThat(JSON.readTree(after.body()).at("/evaluation/policyHash").asString())
                    .isEqualTo(active.path("policyHash").asString());
        }
        var saved = call("GET", ApiPaths.TRANSACTIONS + "/before", producer.key(), null, null);
        var replay = call("POST", ApiPaths.TRANSACTIONS, producer.key(), firstBody, null);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(saved.body()).isEqualTo(first.body());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(context.getStartupDate()).isEqualTo(startup);
    }

    @Test
    void corruptInactiveVersionCannotBeReactivatedThroughAValidCandidate() throws Exception {
        var candidate = (ObjectNode) JSON.readTree(sample());
        ((ObjectNode) candidate.at("/rules/transactionVelocity"))
                .put(
                        "maxTransactions",
                        ThreadLocalRandom.current().nextInt(1000, Integer.MAX_VALUE));
        String document = JSON.writeValueAsString(candidate);
        String hash = loader.read(document).policyHash();
        // The privileged SQL path can bypass Java validation; keep this bad version inactive.
        database("fraud_policy_admin")
                .queryForObject(
                        "SELECT fraud.activate_policy(?, ?, false)",
                        String.class,
                        hash,
                        "schemaVersion: 1");
        activateFromCli(ORIGINAL);
        var before = state();
        assertThat(before.hash()).isNotEqualTo(hash);

        assertProblem(
                call(
                        "PUT",
                        ApiPaths.ADMIN_POLICY,
                        operator.key(),
                        document,
                        etag(before.revision())),
                503,
                "DEPENDENCY_UNAVAILABLE");
        assertThat(state()).isEqualTo(before);
        assertThat(JSON.readTree(current(operator).body()).path("policyHash").asString())
                .isEqualTo(before.hash());
    }

    @Test
    void inspectionPermissionDoesNotGrantAdministrationAndRevocationAppliesImmediately()
            throws Exception {
        var before = state();
        assertProblem(
                call("GET", ApiPaths.ADMIN_POLICY, producer.key(), null, null), 403, "FORBIDDEN");
        assertProblem(
                call("GET", ApiPaths.ADMIN_POLICY, null, null, null), 401, "INVALID_CREDENTIAL");
        assertProblem(
                call(
                        "GET",
                        ApiPaths.ADMIN_POLICY,
                        operator.keyId() + "." + "f".repeat(64),
                        null,
                        null),
                401,
                "INVALID_CREDENTIAL");
        assertThat(current(operator).statusCode()).isEqualTo(200);
        database("fraud_security_admin")
                .update(
                        "UPDATE fraud.api_credentials SET revoked=true WHERE key_id=?",
                        operator.keyId());
        assertProblem(
                call(
                        "PUT",
                        ApiPaths.ADMIN_POLICY,
                        operator.key(),
                        sample(),
                        etag(before.revision())),
                401,
                "INVALID_CREDENTIAL");
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void realRedisQuotaIsSharedByAllAdminOperationsAndDoesNotSpendTheInspectionBucket()
            throws Exception {
        var before = state();
        var caller = authenticator.authenticate(operator.key());
        String key = SharedRateLimiter.key(caller, IngressRoute.POLICY_ADMIN);
        redis.opsForHash()
                .putAll(
                        key,
                        Map.of(
                                "tokens",
                                "0",
                                "time",
                                Long.toString(System.currentTimeMillis() + 60000)));
        for (var route :
                new String[][] {
                    {"GET", ApiPaths.ADMIN_POLICY},
                    {"POST", ApiPaths.ADMIN_POLICY_VALIDATE},
                    {"PUT", ApiPaths.ADMIN_POLICY}
                }) {
            var response =
                    call(route[0], route[1], operator.key(), sample(), etag(before.revision()));
            assertProblem(response, 429, "RATE_LIMITED");
            assertThat(response.headers().firstValue("Retry-After")).contains("1");
        }
        assertThat(call("GET", ApiPaths.RULES, producer.key(), null, null).statusCode())
                .isEqualTo(200);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void redisOutagePreventsActivationAndRecoversWithoutPolicyChanges() throws Exception {
        var before = state();
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertProblem(
                    call(
                            "PUT",
                            ApiPaths.ADMIN_POLICY,
                            operator.key(),
                            sample(),
                            etag(before.revision())),
                    503,
                    "LIMITER_UNAVAILABLE");
            assertThat(call("GET", ApiPaths.LIVE, null, null, null).statusCode()).isEqualTo(200);
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(current(operator).statusCode()).isEqualTo(200);
        assertThat(state()).isEqualTo(before);
    }

    private record Identity(String keyId, String principal, String scope) {
        String key() {
            return keyId + "." + SECRET;
        }
    }

    private record State(long versions, long activations, long revision, String hash) {}

    private Identity identity(String roles) {
        String id = UUID.randomUUID().toString();
        var identity = new Identity(id, "principal-" + id, "scope-" + id);
        var security = database("fraud_security_admin");
        security.update(
                "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources)"
                        + " VALUES (?,?,true,?::text[],ARRAY[]::text[])",
                identity.principal(),
                identity.scope(),
                roles);
        security.update(
                "INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at)"
                        + " VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')",
                identity.keyId(),
                identity.principal(),
                HexFormat.of().formatHex(CredentialAuthenticator.digest(SECRET)));
        return identity;
    }

    private State state() {
        return runtime.queryForObject(
                "SELECT (SELECT count(*) FROM fraud.policy_versions) versions,"
                        + " (SELECT count(*) FROM fraud.policy_activations) activations, revision, policy_hash"
                        + " FROM fraud.policy_head WHERE singleton",
                (row, index) ->
                        new State(
                                row.getLong("versions"),
                                row.getLong("activations"),
                                row.getLong("revision"),
                                row.getString("policy_hash")));
    }

    private JdbcTemplate database(String role) {
        return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), role, PASSWORD));
    }

    private void activateFromCli(String policy) {
        database("fraud_policy_admin")
                .queryForObject(
                        "SELECT fraud.activate_policy(?, ?, false)",
                        String.class,
                        loader.read(policy).policyHash(),
                        policy);
    }

    private HttpResponse<String> current(Identity identity) throws Exception {
        return call("GET", ApiPaths.ADMIN_POLICY, identity.key(), null, null);
    }

    private HttpRequest.Builder request(
            String method, String path, String key, String body, String etag) {
        var request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(5));
        if (key != null) request.header("X-API-Key", key);
        if (etag != null) request.header("If-Match", etag);
        if (body != null) request.header("Content-Type", "application/json");
        return request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> call(
            String method, String path, String key, String body, String etag) throws Exception {
        return http.send(
                request(method, path, key, body, etag).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String etag(long revision) {
        return "\"policy-revision-" + revision + "\"";
    }

    private static String sample() {
        return JSON.writeValueAsString(YAML.read(ORIGINAL, PolicyLoader.Document.class));
    }

    private static String transaction(String event, String customer, String instrument) {
        return """
                {"eventId":"%s","customerId":"%s","instrumentId":"%s","transactionType":"CARD_PURCHASE",
                 "status":"BOOKED","channel":"ECOMMERCE","amount":"26000.00","currency":"ZAR",
                 "category":"GROCERIES","occurredAt":"%s"}
                """
                .formatted(
                        event, customer, instrument, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(
                        value -> assertThat(value).startsWith("application/problem+json"));
        var problem = JSON.readTree(response.body());
        assertThat(problem.path("code").asString()).isEqualTo(code);
        assertThat(problem.path("outcome").asString()).isEqualTo("NOT_ACCEPTED");
        assertThat(response.body()).doesNotContain(SECRET, PASSWORD, "SQLException");
    }
}
