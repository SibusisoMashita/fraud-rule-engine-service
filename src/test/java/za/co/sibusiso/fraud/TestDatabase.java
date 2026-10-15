package za.co.sibusiso.fraud;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

public final class TestDatabase {
    public static final String PASSWORD = "synthetic-test-password";
    private TestDatabase() { }
    public static void initialize(PostgreSQLContainer DATABASE) throws SQLException {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE fraud_migrator LOGIN PASSWORD 'synthetic-test-password'");
            statement.execute("CREATE ROLE fraud_runtime LOGIN PASSWORD 'synthetic-test-password'");
            statement.execute("CREATE ROLE fraud_security_admin LOGIN PASSWORD 'synthetic-test-password'");
            statement.execute("CREATE ROLE fraud_policy_admin LOGIN PASSWORD 'synthetic-test-password'");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA fraud AUTHORIZATION fraud_migrator");
            statement.execute("GRANT USAGE ON SCHEMA fraud TO fraud_runtime");
        }
        var flyway = Flyway.configure().dataSource(DATABASE.getJdbcUrl(), "fraud_migrator", PASSWORD)
                .defaultSchema("fraud").createSchemas(false).load();
        assertThat(Flyway.configure().dataSource(DATABASE.getJdbcUrl(), "fraud_migrator", PASSWORD)
                .defaultSchema("fraud").createSchemas(false).target("2").load().migrate().migrationsExecuted).isEqualTo(2);
        var yaml = new za.co.sibusiso.fraud.configuration.StrictYaml();
        var resource = new org.springframework.core.io.ClassPathResource("config/policy.example.yaml");
        String policyText = yaml.readText(resource);
        var loader = new za.co.sibusiso.fraud.configuration.PolicyLoader(yaml,
                za.co.sibusiso.fraud.configuration.RuleRegistry.of(
                        za.co.sibusiso.fraud.configuration.BuiltInRules.definitions(), 64));
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "fraud_policy_admin", PASSWORD);
                var statement = connection.prepareStatement("SELECT fraud.activate_policy(?, ?, true)")) {
            statement.setString(1, loader.read(policyText).policyHash());
            statement.setString(2, policyText);
            statement.executeQuery().close();
        }
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), "fraud_runtime", PASSWORD);
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT policy_hash FROM fraud.policy_head WHERE singleton")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo(loader.read(policyText).policyHash());
        }
    }
}
