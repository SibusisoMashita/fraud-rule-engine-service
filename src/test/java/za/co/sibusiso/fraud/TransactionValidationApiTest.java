package za.co.sibusiso.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import za.co.sibusiso.fraud.ingress.security.CredentialAuthenticator;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionValidationApiTest {
    private static final String PASSWORD = TestDatabase.PASSWORD;
    private static final String SECRET = "d".repeat(64);
    private static final String PRIVATE_VALUE = "PRIVATE_REVIEW_VALUE";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    @Autowired JdbcTemplate observer;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-02-30T12:00:00Z",
                "2026-13-01T12:00:00Z",
                "2026-10-23T12:00:00+25:00",
                "2026-10-23T12:00:00+18:01",
                "2026-10-23T12:00:00.1234567Z",
                "2026-10-23T12:00:00",
                "0000-01-01T00:00:00Z"
            })
    void invalidCalendarDatesOffsetsAndPrecisionAreSafeSemanticErrors(String timestamp)
            throws Exception {
        var who = identity("{INGEST,READ}");
        var input = transaction();
        input.put("occurredAt", timestamp);

        var response = post(who, JSON.writeValueAsString(input));

        assertValidation(response, "INVALID_TRANSACTION", "occurredAt", "INVALID_TIMESTAMP");
        assertThat(response.body())
                .doesNotContain(timestamp, "DateTimeParseException", "could not be parsed");
        assertNoWrites(who);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("domainFailures")
    void domainFailuresIdentifyOneSafeFieldWithoutPersisting(InvalidInput invalid)
            throws Exception {
        var who = identity("{INGEST,READ}");
        var input = transaction();
        invalid.change().accept(input);

        var response = post(who, JSON.writeValueAsString(input));

        assertValidation(response, "INVALID_TRANSACTION", invalid.field(), invalid.code());
        assertNoWrites(who);
    }

    private static Stream<InvalidInput> domainFailures() {
        return Stream.of(
                invalid(
                        "eventId",
                        "INVALID_IDENTIFIER",
                        input -> input.put("eventId", "invalid " + PRIVATE_VALUE)),
                invalid(
                        "customerId",
                        "INVALID_IDENTIFIER",
                        input -> input.put("customerId", "invalid " + PRIVATE_VALUE)),
                invalid(
                        "instrumentId",
                        "INVALID_IDENTIFIER",
                        input -> input.put("instrumentId", "invalid " + PRIVATE_VALUE)),
                invalid(
                        "transactionType",
                        "INVALID_VALUE",
                        input -> input.put("transactionType", PRIVATE_VALUE)),
                invalid("status", "INVALID_VALUE", input -> input.put("status", PRIVATE_VALUE)),
                invalid("channel", "INVALID_VALUE", input -> input.put("channel", PRIVATE_VALUE)),
                invalid("amount", "INVALID_AMOUNT", input -> input.put("amount", "0")),
                invalid("amount", "INVALID_AMOUNT", input -> input.put("amount", "-1.00")),
                invalid("amount", "INVALID_AMOUNT", input -> input.put("amount", "1e3")),
                invalid("amount", "INVALID_AMOUNT", input -> input.put("amount", PRIVATE_VALUE)),
                invalid("currency", "INVALID_CURRENCY", input -> input.put("currency", "zar")),
                invalid(
                        "category",
                        "INVALID_TEXT",
                        input -> input.put("category", "\n" + PRIVATE_VALUE)),
                invalid(
                        "physicalLocation.latitude",
                        "INVALID_COORDINATE",
                        input -> physical(input).put("latitude", new BigDecimal("90.0001"))),
                invalid(
                        "physicalLocation.longitude",
                        "INVALID_COORDINATE",
                        input -> physical(input).put("longitude", new BigDecimal("-180.0001"))),
                invalid(
                        "physicalLocation.accuracyKm",
                        "INVALID_ACCURACY",
                        input -> physical(input).put("accuracyKm", new BigDecimal("-0.1"))),
                invalid(
                        "physicalLocation.source",
                        "INVALID_VALUE",
                        input -> physical(input).put("source", PRIVATE_VALUE)),
                invalid(
                        "physicalLocation.city",
                        "INVALID_TEXT",
                        input -> physical(input).put("city", "\n" + PRIVATE_VALUE)),
                invalid("channel", "TYPE_CHANNEL_MISMATCH", input -> input.put("channel", "ATM")),
                invalid(
                        "physicalLocation",
                        "ONLINE_PHYSICAL_LOCATION",
                        input -> {
                            physical(input);
                            input.put("channel", "ECOMMERCE");
                        }),
                invalid(
                        "physicalLocation.source",
                        "LOCATION_SOURCE_CHANNEL_MISMATCH",
                        input -> physical(input).put("source", "VERIFIED_ATM_TERMINAL")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("policyFailures")
    void newEventPolicyRejectionsCarrySafeFieldErrors(InvalidInput invalid) throws Exception {
        var who = identity("{INGEST,READ}");
        var input = transaction();
        invalid.change().accept(input);

        var response = post(who, JSON.writeValueAsString(input));

        assertValidation(response, invalid.code(), invalid.field(), invalid.code());
        assertNoWrites(who);
    }

    private static Stream<InvalidInput> policyFailures() {
        return Stream.of(
                invalid("currency", "UNSUPPORTED_CURRENCY", input -> input.put("currency", "USD")),
                invalid("amount", "AMOUNT_SCALE", input -> input.put("amount", "1.001")),
                invalid("amount", "AMOUNT_LIMIT", input -> input.put("amount", "1000000000000.00")),
                invalid(
                        "occurredAt",
                        "EVENT_TOO_OLD",
                        input ->
                                input.put(
                                        "occurredAt",
                                        time(Instant.now().minus(8, ChronoUnit.DAYS)))),
                invalid(
                        "occurredAt",
                        "EVENT_IN_FUTURE",
                        input -> input.put("occurredAt", time(Instant.now().plusSeconds(600)))));
    }

    @Test
    void malformedUnknownMissingAndWrongTypeFieldsKeepTransportErrorsWithoutReflectedKeys()
            throws Exception {
        var who = identity("{INGEST,READ}");
        var inputs =
                List.of(
                        "{\"" + PRIVATE_VALUE + "\":",
                        changed(input -> input.put(PRIVATE_VALUE, "secret")),
                        changed(input -> input.remove("eventId")),
                        changed(input -> input.put("amount", new BigDecimal("1.00"))),
                        changed(input -> input.put("customerId", null)),
                        changed(input -> physical(input).put("latitude", PRIVATE_VALUE)),
                        changed(input -> input.put("physicalLocation", null)));

        for (String input : inputs) {
            var response = post(who, input);
            assertProblem(response, 400, null);
            assertThat(JSON.readTree(response.body()).has("errors")).isFalse();
        }
        assertNoWrites(who);
    }

    @Test
    void onlyOneDeterministicErrorIsReturnedWhenSeveralSemanticFieldsAreInvalid() throws Exception {
        var who = identity("{INGEST,READ}");
        String input =
                changed(
                        fields -> {
                            fields.put("eventId", "invalid " + PRIVATE_VALUE);
                            fields.put("amount", PRIVATE_VALUE);
                            fields.put("occurredAt", PRIVATE_VALUE);
                        });

        var first = post(who, input);
        var second = post(who, input);

        assertProblem(first, 422, "INVALID_TRANSACTION");
        assertProblem(second, 422, "INVALID_TRANSACTION");
        assertThat(JSON.readTree(first.body()).get("errors").size()).isEqualTo(1);
        assertThat(JSON.readTree(second.body()).get("errors"))
                .isEqualTo(JSON.readTree(first.body()).get("errors"));
        assertNoWrites(who);
    }

    @Test
    void authenticationAuthorizationAndDuplicateFailuresDoNotClaimFieldValidation()
            throws Exception {
        var who = identity("{INGEST,READ}");
        String input = changed(fields -> fields.put("eventId", "replay"));
        var unauthenticated = request("POST", "/api/v1/transactions", "unknown." + SECRET, input);
        assertProblem(unauthenticated, 401, "INVALID_CREDENTIAL");
        assertThat(JSON.readTree(unauthenticated.body()).has("errors")).isFalse();

        var reader = identity("{READ}");
        var forbidden = post(reader, input);
        assertProblem(forbidden, 403, "FORBIDDEN");
        assertThat(JSON.readTree(forbidden.body()).has("errors")).isFalse();

        var accepted = post(who, input);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        var replay = post(who, input);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(accepted.body());

        var conflict = post(who, input.replace("\"1.00\"", "\"2.00\""));
        assertProblem(conflict, 409, "EVENT_CONTENT_CONFLICT");
        assertThat(JSON.readTree(conflict.body()).has("errors")).isFalse();
        assertThat(
                        observer.queryForObject(
                                "SELECT count(*) FROM fraud.transactions WHERE scope=?",
                                Integer.class,
                                who.scope()))
                .isEqualTo(1);
        assertNoWrites(reader);
    }

    private void assertValidation(
            HttpResponse<String> response, String topCode, String field, String code) {
        assertProblem(response, 422, topCode);
        JsonNode errors = JSON.readTree(response.body()).get("errors");
        assertThat(errors).isNotNull();
        assertThat(errors.size()).isEqualTo(1);
        JsonNode error = errors.get(0);
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("field", "code", "message");
        assertThat(error.get("field").asString()).isEqualTo(field).hasSizeLessThanOrEqualTo(64);
        assertThat(error.get("code").asString()).isEqualTo(code).hasSizeLessThanOrEqualTo(64);
        assertThat(error.get("message").asString()).isNotBlank().hasSizeLessThanOrEqualTo(160);
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode problem = JSON.readTree(response.body());
        if (code != null) assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("outcome").asString()).isEqualTo("NOT_ACCEPTED");
        assertThat(problem.get("retryable").asBoolean()).isFalse();
        assertThat(response.headers().firstValue("X-Correlation-ID"))
                .contains(problem.get("correlationId").asString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(
                        value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(response.body())
                .doesNotContain(PRIVATE_VALUE, SECRET, "Exception", "java.time.", " at [Source:");
    }

    private void assertNoWrites(Identity who) {
        for (String table :
                List.of(
                        "transactions",
                        "evaluations",
                        "rule_results",
                        "instrument_owners",
                        "customer_processing_state")) {
            assertThat(
                            observer.queryForObject(
                                    "SELECT count(*) FROM fraud." + table + " WHERE scope=?",
                                    Integer.class,
                                    who.scope()))
                    .as(table)
                    .isZero();
        }
    }

    private record Identity(String scope, String key) {}

    private Identity identity(String roles) {
        String id = UUID.randomUUID().toString();
        String scope = "validation-" + id;
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                DATABASE.getJdbcUrl(), "fraud_security_admin", PASSWORD));
        admin.update(
                "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) VALUES (?,?,true,?::text[],ARRAY['VERIFIED_CARD_PRESENT_TERMINAL','VERIFIED_ATM_TERMINAL'])",
                id,
                scope,
                roles);
        admin.update(
                "INSERT INTO fraud.api_credentials(key_id,principal_id,secret_hash,valid_from,expires_at) VALUES (?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 hour')",
                id,
                id,
                HexFormat.of().formatHex(CredentialAuthenticator.digest(SECRET)));
        return new Identity(scope, id + "." + SECRET);
    }

    private HttpResponse<String> post(Identity who, String body) throws Exception {
        return request("POST", "/api/v1/transactions", who.key(), body);
    }

    private HttpResponse<String> request(String method, String path, String key, String body)
            throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-API-Key", key)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String changed(Consumer<Map<String, Object>> change) {
        var input = transaction();
        change.accept(input);
        return JSON.writeValueAsString(input);
    }

    private static Map<String, Object> transaction() {
        var input = new LinkedHashMap<String, Object>();
        input.put("eventId", "validation-event");
        input.put("customerId", "validation-customer");
        input.put("instrumentId", "validation-card");
        input.put("transactionType", "CARD_PURCHASE");
        input.put("status", "BOOKED");
        input.put("channel", "ECOMMERCE");
        input.put("amount", "1.00");
        input.put("currency", "ZAR");
        input.put("category", "GROCERIES");
        input.put("occurredAt", time(Instant.now()));
        return input;
    }

    private static Map<String, Object> physical(Map<String, Object> input) {
        var location = new LinkedHashMap<String, Object>();
        location.put("latitude", new BigDecimal("-33.9249"));
        location.put("longitude", new BigDecimal("18.4241"));
        location.put("accuracyKm", new BigDecimal("0.1"));
        location.put("source", "VERIFIED_CARD_PRESENT_TERMINAL");
        location.put("city", "Cape Town");
        input.put("channel", "CARD_PRESENT");
        input.put("physicalLocation", location);
        return location;
    }

    private static String time(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).toString();
    }

    private record InvalidInput(String field, String code, Consumer<Map<String, Object>> change) {
        @Override
        public String toString() {
            return field + ": " + code;
        }
    }

    private static InvalidInput invalid(
            String field, String code, Consumer<Map<String, Object>> change) {
        return new InvalidInput(field, code, change);
    }
}
