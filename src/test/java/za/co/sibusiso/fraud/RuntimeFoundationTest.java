package za.co.sibusiso.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeFoundationTest {
    private static final String PASSWORD = "synthetic-test-password";

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
    static void dependencies(DynamicPropertyRegistry properties) throws SQLException {
        TestDatabase.initialize(DATABASE);
        properties.add("fraud.cursor-signing-key", () -> "a".repeat(64));
        properties.add(
                "spring.datasource.url",
                () -> DATABASE.getJdbcUrl() + "&connectTimeout=1&socketTimeout=2");
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
        assertThat(dataSource.getMaximumPoolSize())
                .isEqualTo(limits.operations().dbPoolMaxConnectionsPerReplica());
        assertThat(dataSource.getConnectionTimeout())
                .isEqualTo(limits.operations().poolAcquireTimeoutMillis());
        assertThat(dataSource.getInitializationFailTimeout())
                .isEqualTo(limits.operations().poolAcquireTimeoutMillis());
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("1500ms");
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("1s");
        assertThat(policies.current().rules()).hasSize(5);
        assertThat(policies.current().policyHash()).matches("[a-f0-9]{64}");
    }

    @Test
    void defaultRuntimeKeepsOneFinancialDatasourceAndNoPolicyManagementBeans(
            @Autowired org.springframework.context.ApplicationContext context) {
        assertThat(context.getBeansOfType(javax.sql.DataSource.class).values())
                .containsExactly(dataSource);
        assertThat(context.getBeansOfType(JdbcTemplate.class).values()).containsExactly(jdbc);
        assertThat(
                        context.getBeansOfType(
                                        org.springframework.transaction.PlatformTransactionManager
                                                .class)
                                .values())
                .singleElement()
                .isInstanceOfSatisfying(
                        org.springframework.jdbc.datasource.DataSourceTransactionManager.class,
                        manager -> assertThat(manager.getDataSource()).isSameAs(dataSource));
        assertThat(
                        context.getBeansOfType(
                                za.co.sibusiso.fraud.policy.management.JdbcPolicyWriter.class))
                .isEmpty();
        assertThat(
                        context.getBeansOfType(
                                za.co.sibusiso.fraud.policy.management.PolicyManagementService
                                        .class))
                .isEmpty();
        assertThat(
                        context.getBeansOfType(
                                za.co.sibusiso.fraud.ingress.PolicyManagementController.class))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "policy-admin"})
    void startupWaitsForColdConnectionWithoutExtendingRuntimeAcquisition(String profile)
            throws Exception {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            var environment =
                    new org.springframework.mock.env.MockEnvironment()
                            .withProperty(
                                    "fraud.limits-location",
                                    "classpath:config/service.example.yaml");
            environment.setActiveProfiles(profile);
            context.setEnvironment(environment);
            new za.co.sibusiso.fraud.configuration.LimitsInitializer().initialize(context);
            long acquire =
                    environment.getRequiredProperty(
                            "spring.datasource.hikari.connection-timeout", Long.class);
            long initialize =
                    environment.getRequiredProperty(
                            "spring.datasource.hikari.initialization-fail-timeout", Long.class);
            assertThat(acquire).isEqualTo(limits.operations().poolAcquireTimeoutMillis());
            var slowDatabase =
                    new org.springframework.jdbc.datasource.AbstractDataSource() {
                        @Override
                        public java.sql.Connection getConnection() throws SQLException {
                            try {
                                Thread.sleep(acquire * 2);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new SQLException(
                                        "Interrupted delayed connection", interrupted);
                            }
                            return DriverManager.getConnection(
                                    DATABASE.getJdbcUrl(),
                                    DATABASE.getUsername(),
                                    DATABASE.getPassword());
                        }

                        @Override
                        public java.sql.Connection getConnection(String user, String password)
                                throws SQLException {
                            return getConnection();
                        }
                    };
            var configuration = new com.zaxxer.hikari.HikariConfig();
            configuration.setDataSource(slowDatabase);
            configuration.setMaximumPoolSize(1);
            configuration.setConnectionTimeout(acquire);
            configuration.setValidationTimeout(
                    environment.getRequiredProperty(
                            "spring.datasource.hikari.validation-timeout", Long.class));
            configuration.setInitializationFailTimeout(initialize);
            try (var pool = new com.zaxxer.hikari.HikariDataSource(configuration);
                    var connection = pool.getConnection();
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT 1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(pool.getInitializationFailTimeout()).isEqualTo(acquire);
                assertThat(pool.getConnectionTimeout()).isEqualTo(acquire);
                // The single connection stays borrowed; ordinary requests still time out.
                assertThatThrownBy(pool::getConnection)
                        .isInstanceOf(SQLTransientConnectionException.class)
                        .hasMessageContaining("request timed out");
            }
        }
    }

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void exposesOnlyMinimalHealth() throws Exception {
        assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/evaluations").statusCode()).isEqualTo(401);
        assertThat(get("/login").statusCode()).isEqualTo(403);
        var response =
                client.send(
                        HttpRequest.newBuilder(uri("/health/live"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test
    void runtimeCannotCreateTablesOrAssumeMigrationRole() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE fraud.forbidden (id integer)"))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE public.forbidden (id integer)"))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.execute("SET ROLE fraud_migrator"))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE fraud.service_metadata SET version = 2"))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM fraud.flyway_schema_history",
                                        Integer.class))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo("fraud_runtime");
    }

    @Test
    void schemaMismatchPreventsReadiness() throws Exception {
        changeSchemaVersion(99, 9);
        try {
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            changeSchemaVersion(9, 99);
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
        long revision =
                jdbc.queryForObject(
                        "SELECT revision FROM fraud.policy_head WHERE singleton", Long.class);
        String updated = original.replace("25000.00", "25000.01");
        String nextHash = policyHash(updated);
        try {
            activate(nextHash, updated, false);
            assertThat(policies.current().policyHash())
                    .isEqualTo(nextHash)
                    .isNotEqualTo(before.policyHash());
            assertThat(before.policyHash()).isEqualTo(policyHash(original));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT revision FROM fraud.policy_head WHERE singleton",
                                    Long.class))
                    .isEqualTo(revision + 1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT policy_hash FROM fraud.policy_activations WHERE revision = ?",
                                    String.class,
                                    revision + 1))
                    .isEqualTo(nextHash);
            assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
            activate(before.policyHash(), original, true);
            assertThat(policies.current().policyHash()).isEqualTo(nextHash);
            assertThatThrownBy(() -> jdbc.update("UPDATE fraud.policy_head SET policy_hash = NULL"))
                    .rootCause()
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(
                            () ->
                                    jdbc.queryForObject(
                                            "SELECT fraud.activate_policy(?, ?, false)",
                                            String.class,
                                            before.policyHash(),
                                            original))
                    .rootCause()
                    .hasMessageContaining("permission denied");
            try (var connection =
                            DriverManager.getConnection(
                                    DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD);
                    var statement = connection.createStatement()) {
                assertThatThrownBy(
                                () ->
                                        statement.executeUpdate(
                                                "UPDATE fraud.policy_versions SET policy_yaml = 'changed'"))
                        .hasMessageContaining("permission denied");
                assertThatThrownBy(
                                () ->
                                        statement.executeUpdate(
                                                "DELETE FROM fraud.policy_activations"))
                        .hasMessageContaining("permission denied");
            }
        } finally {
            activate(before.policyHash(), original, false);
        }
    }

    @Test
    void uncommittedActivationIsInvisibleAndRollbackPreservesHeadAndAudit() throws Exception {
        String original = policyText();
        String before = policies.current().policyHash();
        long revision =
                jdbc.queryForObject(
                        "SELECT revision FROM fraud.policy_head WHERE singleton", Long.class);
        long auditCount =
                jdbc.queryForObject("SELECT count(*) FROM fraud.policy_activations", Long.class);
        String changed = original.replace("25000.00", "25000.02");
        String hash = policyHash(changed);
        try (var connection =
                DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD)) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement("SELECT fraud.activate_policy(?, ?, false)")) {
                statement.setString(1, hash);
                statement.setString(2, changed);
                statement.executeQuery().close();
            }
            assertThat(policies.current().policyHash()).isEqualTo(before);
            connection.rollback();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM fraud.policy_head WHERE singleton",
                                Long.class))
                .isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fraud.policy_activations", Long.class))
                .isEqualTo(auditCount);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM fraud.policy_versions WHERE policy_hash = ?",
                                Integer.class,
                                hash))
                .isZero();
    }

    @Test
    void invalidDatabasePolicyClosesReadinessWithoutUsingCachedPolicyAndRecovers()
            throws Exception {
        String original = policyText();
        String before = policies.current().policyHash();
        try {
            activate("0".repeat(64), "schemaVersion: 999", false);
            assertThatThrownBy(policies::current)
                    .isInstanceOf(za.co.sibusiso.fraud.policy.PolicyUnavailableException.class);
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            activate(before, original, false);
        }
        assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
    }

    @Test
    void aPolicyAboveTheLocationSafetyCeilingClosesReadinessWithoutCachedFallback()
            throws Exception {
        String original = policyText();
        String previous = policies.current().policyHash();
        String incompatible =
                original.replace(
                        "maxCandidates: 500",
                        "maxCandidates: "
                                + (limits.api().maxLocationCandidatesPerEvaluation() + 1));
        try {
            activate(policyHash(incompatible), incompatible, false);
            assertThatThrownBy(policies::current)
                    .isInstanceOf(za.co.sibusiso.fraud.policy.PolicyUnavailableException.class);
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            activate(previous, original, false);
        }
        assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
    }

    private static String policyText() {
        return new za.co.sibusiso.fraud.configuration.StrictYaml()
                .readText(
                        new org.springframework.core.io.ClassPathResource(
                                "config/policy.example.yaml"));
    }

    private static String policyHash(String text) {
        return new za.co.sibusiso.fraud.policy.definition.PolicyLoader(
                        new za.co.sibusiso.fraud.configuration.StrictYaml(),
                        za.co.sibusiso.fraud.policy.definition.RuleRegistry.of(
                                za.co.sibusiso.fraud.policy.definition.BuiltInRules.definitions(),
                                64))
                .read(text)
                .policyHash();
    }

    private static void activate(String hash, String text, boolean seed) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD);
                var statement =
                        connection.prepareStatement("SELECT fraud.activate_policy(?, ?, ?)")) {
            statement.setString(1, hash);
            statement.setString(2, text);
            statement.setBoolean(3, seed);
            statement.executeQuery().close();
        }
    }

    private void changeSchemaVersion(int next, int previous) throws SQLException {
        try (var connection =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(), "fraud_migrator", PASSWORD);
                var statement =
                        connection.prepareStatement(
                                "UPDATE fraud.service_metadata SET version = ? WHERE version = ?")) {
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
        return client.send(
                HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
