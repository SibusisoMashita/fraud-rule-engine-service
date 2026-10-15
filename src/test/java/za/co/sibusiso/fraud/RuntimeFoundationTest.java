package za.co.sibusiso.fraud;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.flywaydb.core.Flyway;
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
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE fraud_migrator LOGIN PASSWORD 'synthetic-test-password'");
            statement.execute("CREATE ROLE fraud_runtime LOGIN PASSWORD 'synthetic-test-password'");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA fraud AUTHORIZATION fraud_migrator");
            statement.execute("GRANT USAGE ON SCHEMA fraud TO fraud_runtime");
        }
        var flyway = Flyway.configure().dataSource(DATABASE.getJdbcUrl(), "fraud_migrator", PASSWORD)
                .defaultSchema("fraud").createSchemas(false).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        properties.add("spring.datasource.url", () -> DATABASE.getJdbcUrl() + "&connectTimeout=1&socketTimeout=2");
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password", () -> PASSWORD);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void exposesOnlyMinimalHealth() throws Exception {
        assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        assertResponse("/health/ready", 200, "{\"status\":\"UP\"}");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/evaluations").statusCode()).isEqualTo(403);
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
        changeSchemaVersion(2, 1);
        try {
            assertResponse("/health/ready", 503, "{\"status\":\"DOWN\"}");
            assertResponse("/health/live", 200, "{\"status\":\"UP\"}");
        } finally {
            changeSchemaVersion(1, 2);
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
