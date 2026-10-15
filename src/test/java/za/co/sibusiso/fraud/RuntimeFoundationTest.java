package za.co.sibusiso.fraud;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeFoundationTest {
    private static final String PASSWORD = "synthetic-test-password";
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:8.2@sha256:47670742d7924adbcdb404d1288b6327815b23141969c0939b8e5d61f78c2634"))
            .withExposedPorts(6379).withCommand("redis-server", "--requirepass", PASSWORD);

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry properties) throws SQLException {
        TestDatabase.initialize(DATABASE);
        properties.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&connectTimeout=1&socketTimeout=2");
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password", () -> PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired za.co.sibusiso.fraud.configuration.ServiceLimits limits;
    @Autowired za.co.sibusiso.fraud.policy.ActivePolicyProvider policies;
    @Autowired com.zaxxer.hikari.HikariDataSource dataSource;

    @Test
    void validatedSettingsDriveTheRealPoolAndPolicyActivation() {
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(limits.operations().dbPoolMaxConnectionsPerReplica());
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(limits.operations().poolAcquireTimeoutMillis());
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("1500ms");
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("1s");
        assertThat(policies.current().rules()).hasSize(5);
        assertThat(policies.current().policyHash()).matches("[a-f0-9]{64}");
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void exposesOnlyMinimalHealth() throws Exception {
        assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/evaluations").statusCode()).isEqualTo(401);
        assertThat(get("/login").statusCode()).isEqualTo(403);
        var response = client.send(HttpRequest.newBuilder(uri("/health/live"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test
    void runtimeCannotCreateTablesOrAssumeMigrationRole() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE fraud.forbidden (id integer)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE public.forbidden (id integer)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.execute("SET ROLE fraud_migrator"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE fraud.service_metadata SET version = 2"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT count(*) FROM fraud.flyway_schema_history", Integer.class))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("fraud_runtime");
    }

    @Test
    void schemaMismatchPreventsReadiness() throws Exception {
        changeSchemaVersion(99, 7);
        try {
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            changeSchemaVersion(7, 99);
        }
    }

    @Test
    void redisOutageClosesReadinessAndRecovers() throws Exception {
        var docker = REDIS.getDockerClient();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        awaitReady();
    }

    @Test
    void databaseOutageClosesReadinessAndRecovers() throws Exception {
        var docker = DATABASE.getDockerClient();
        docker.pauseContainerCmd(DATABASE.getContainerId()).exec();
        try {
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            docker.unpauseContainerCmd(DATABASE.getContainerId()).exec();
        }
        awaitReady();
    }

    @Test
    void activatesPolicyWithoutRestartAndPreservesPublishedVersions() throws Exception {
        String original = policyText();
        var before = policies.current();
        long revision = jdbc.queryForObject("SELECT revision FROM fraud.policy_head WHERE singleton", Long.class);
        String updated = original.replace("25000.00", "25000.01");
        String nextHash = policyHash(updated);
        try {
            activate(nextHash, updated, false);
            assertThat(policies.current().policyHash()).isEqualTo(nextHash).isNotEqualTo(before.policyHash());
            assertThat(before.policyHash()).isEqualTo(policyHash(original));
            assertThat(jdbc.queryForObject("SELECT revision FROM fraud.policy_head WHERE singleton", Long.class)).isEqualTo(revision + 1);
            assertThat(jdbc.queryForObject("SELECT policy_hash FROM fraud.policy_activations WHERE revision = ?", String.class, revision + 1))
                    .isEqualTo(nextHash);
            assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
            activate(before.policyHash(), original, true);
            assertThat(policies.current().policyHash()).isEqualTo(nextHash);
            assertThatThrownBy(() -> jdbc.update("UPDATE fraud.policy_head SET policy_hash = NULL"))
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.queryForObject("SELECT fraud.activate_policy(?, ?, false)", String.class,
                    before.policyHash(), original)).rootCause().hasMessageContaining("permission denied");
            try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD);
                    var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("UPDATE fraud.policy_versions SET policy_yaml = 'changed'"))
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM fraud.policy_activations"))
                        .hasMessageContaining("permission denied");
            }
        } finally { activate(before.policyHash(), original, false); }
    }

    @Test
    void uncommittedActivationIsInvisibleAndRollbackPreservesHeadAndAudit() throws Exception {
        String original = policyText();
        String before = policies.current().policyHash();
        long revision = jdbc.queryForObject("SELECT revision FROM fraud.policy_head WHERE singleton", Long.class);
        long auditCount = jdbc.queryForObject("SELECT count(*) FROM fraud.policy_activations", Long.class);
        String changed = original.replace("25000.00", "25000.02");
        String hash = policyHash(changed);
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD)) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT fraud.activate_policy(?, ?, false)")) {
                statement.setString(1, hash);
                statement.setString(2, changed);
                statement.executeQuery().close();
            }
            assertThat(policies.current().policyHash()).isEqualTo(before);
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM fraud.policy_head WHERE singleton", Long.class)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fraud.policy_activations", Long.class)).isEqualTo(auditCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fraud.policy_versions WHERE policy_hash = ?", Integer.class, hash)).isZero();
    }

    @Test
    void invalidDatabasePolicyClosesReadinessWithoutUsingCachedPolicyAndRecovers() throws Exception {
        String original = policyText();
        String before = policies.current().policyHash();
        try {
            activate("0".repeat(64), "schemaVersion: 999", false);
            assertThatThrownBy(policies::current).isInstanceOf(za.co.sibusiso.fraud.policy.PolicyUnavailableException.class);
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally { activate(before, original, false); }
        assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
    }

    @Test
    void aPolicyAboveTheLocationSafetyCeilingClosesReadinessWithoutCachedFallback() throws Exception {
        String original=policyText();String previous=policies.current().policyHash();
        String incompatible=original.replace("maxCandidates: 500","maxCandidates: "+(limits.api().maxLocationCandidatesPerEvaluation()+1));
        try {
            activate(policyHash(incompatible),incompatible,false);
            assertThatThrownBy(policies::current).isInstanceOf(za.co.sibusiso.fraud.policy.PolicyUnavailableException.class);
            assertResponse("/health/ready",503,"{\"status\":\"DOWN\"}");assertResponse("/health/live",200,"{\"status\":\"UP\"}");
        }finally{activate(previous,original,false);}
        assertResponse("/health/ready",200,"{\"status\":\"UP\"}");
    }

    private static String policyText() {
        return new za.co.sibusiso.fraud.configuration.StrictYaml().readText(
                new org.springframework.core.io.ClassPathResource("config/policy.example.yaml"));
    }

    private static String policyHash(String text) {
        return new za.co.sibusiso.fraud.configuration.PolicyLoader(new za.co.sibusiso.fraud.configuration.StrictYaml(),
                za.co.sibusiso.fraud.configuration.RuleRegistry.of(za.co.sibusiso.fraud.configuration.BuiltInRules.definitions(), 64))
                .read(text).policyHash();
    }

    private static void activate(String hash, String text, boolean seed) throws SQLException {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD);
                var statement = connection.prepareStatement("SELECT fraud.activate_policy(?, ?, ?)")) {
            statement.setString(1, hash);
            statement.setString(2, text);
            statement.setBoolean(3, seed);
            statement.executeQuery().close();
        }
    }

    private void changeSchemaVersion(int next, int previous) throws SQLException {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "fraud_migrator", PASSWORD);
                var statement = connection.prepareStatement("UPDATE fraud.service_metadata SET version = ? WHERE version = ?")) {
            statement.setInt(1, next);
            statement.setInt(2, previous);
            statement.executeUpdate();
        }
    }

    private void awaitReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (get("/health/ready").statusCode() == 200) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Dependency recovery did not restore readiness");
    }

    private void assertResponse(String path, int status, String body) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(body);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
