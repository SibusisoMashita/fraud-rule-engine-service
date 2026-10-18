package za.co.sibusiso.fraud.policy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import za.co.sibusiso.fraud.TestDatabase;
import za.co.sibusiso.fraud.TestImages;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PolicyActivationMigrationTest {
    @Container
    static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(DockerImageName.parse(TestImages.POSTGRES)
                    .asCompatibleSubstituteFor("postgres"))
                    .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);

    private static final String MIGRATOR = "fraud_migrator";
    private static final String CLI = "fraud_policy_admin";
    private static final String API = "fraud_policy_api";
    private static final String RUNTIME = "fraud_runtime";
    private static final String ACTOR = "policy-admin-a";
    private static final String OTHER_ACTOR = "policy-admin-b";
    private static final String CORE = "fraud._activate_policy(text,text,boolean,bigint,text)";
    private static final String GUARDED = "fraud.activate_policy_if_revision(text,text,bigint,text)";
    private static final AtomicInteger CANDIDATES = new AtomicInteger();
    private static final StrictYaml YAML = new StrictYaml();
    private static final PolicyLoader LOADER =
            new PolicyLoader(YAML, RuleRegistry.of(BuiltInRules.definitions(), 64));
    private static final String SOURCE = YAML.readText(new ClassPathResource("config/policy.example.yaml"));
    private static String originalHash;
    private static String originalVersion;
    private static String originalActivation;
    private static String originalPrincipal;
    private static String runtimeGrants;

    @BeforeAll
    static void upgradeExistingDatabase() throws Exception {
        TestDatabase.initialize(DATABASE, 8);
        originalHash = head().hash();
        insertPrincipal("existing-reader", true, "READ");
        originalVersion = version(originalHash);
        originalActivation = activation(1);
        originalPrincipal = scalar(MIGRATOR,
                "SELECT to_jsonb(p)::text FROM fraud.api_principals p WHERE principal_id='existing-reader'");
        runtimeGrants = runtimeGrants();
        String previousHead = scalar(RUNTIME, "SELECT to_jsonb(h)::text FROM fraud.policy_head h");
        var flyway = Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), MIGRATOR, TestDatabase.PASSWORD)
                .defaultSchema("fraud").target("9").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(scalar(RUNTIME, "SELECT to_jsonb(h)::text FROM fraud.policy_head h"))
                .isEqualTo(previousHead);
        insertPrincipal(ACTOR, true, "POLICY_ADMIN");
        insertPrincipal(OTHER_ACTOR, true, "POLICY_ADMIN");
        insertPrincipal("disabled-admin", false, "POLICY_ADMIN");
    }

    @Test
    void upgradePreservesExistingPolicyIdentityAuditPermissionsAndPrincipalRecords() throws Exception {
        assertThat(scalar(RUNTIME, "SELECT version FROM fraud.service_metadata")).isEqualTo("9");
        assertThat(version(originalHash)).isEqualTo(originalVersion);
        assertThat(activation(1)).isEqualTo(originalActivation);
        assertThat(scalar(RUNTIME,
                "SELECT published_principal_id FROM fraud.policy_versions WHERE policy_hash=?", originalHash))
                .isNull();
        assertThat(scalar(RUNTIME,
                "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=1")).isNull();
        assertThat(scalar(MIGRATOR,
                "SELECT to_jsonb(p)::text FROM fraud.api_principals p WHERE principal_id='existing-reader'"))
                .isEqualTo(originalPrincipal);
        assertThat(runtimeGrants()).isEqualTo(runtimeGrants);
    }

    @Test
    void guardedActivationReturnsRevisionAndRecordsBothApplicationAndDatabaseActors() throws Exception {
        var before = head();
        var candidate = candidate();
        var activated = cas(candidate, before.revision(), ACTOR);
        assertThat(activated).isEqualTo(new Head(before.revision() + 1, candidate.hash()));
        assertThat(head()).isEqualTo(activated);
        assertThat(scalar(API, "SELECT published_principal_id FROM fraud.policy_versions WHERE policy_hash=?",
                candidate.hash())).isEqualTo(ACTOR);
        assertThat(scalar(API, "SELECT published_by FROM fraud.policy_versions WHERE policy_hash=?",
                candidate.hash())).isEqualTo(API);
        assertThat(scalar(API, "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?",
                activated.revision())).isEqualTo(ACTOR);
        assertThat(scalar(API, "SELECT activated_by FROM fraud.policy_activations WHERE revision=?",
                activated.revision())).isEqualTo(API);
    }

    @Test
    void matchingHashAndRevisionAreANoOpWithoutChangingPublicationOrAudit() throws Exception {
        var before = head();
        var source = scalar(API, "SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash=?", before.hash());
        var samePolicy = new Candidate(before.hash(), "# Formatting does not change the policy identity.\n" + source);
        assertThat(LOADER.read(samePolicy.source()).policyHash()).isEqualTo(before.hash());
        String version = scalar(API, "SELECT to_jsonb(v)::text FROM fraud.policy_versions v WHERE policy_hash=?", before.hash());
        String activations = scalar(API, "SELECT count(*) FROM fraud.policy_activations");
        assertThat(cas(samePolicy, before.revision(), OTHER_ACTOR)).isEqualTo(before);
        assertThat(head()).isEqualTo(before);
        assertThat(scalar(API, "SELECT to_jsonb(v)::text FROM fraud.policy_versions v WHERE policy_hash=?", before.hash()))
                .isEqualTo(version);
        assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_activations")).isEqualTo(activations);
    }

    @Test
    void staleRevisionsRejectBothNewPoliciesAndSameHashAfterAnAbaChange() throws Exception {
        var before = head();
        var original = new Candidate(before.hash(),
                scalar(API, "SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash=?", before.hash()));
        var changed = cas(candidate(), before.revision(), ACTOR);
        var returned = cas(original, changed.revision(), ACTOR);
        assertThat(returned.hash()).isEqualTo(before.hash());
        assertThat(returned.revision()).isEqualTo(before.revision() + 2);
        var stale = candidate();
        String audits = scalar(API, "SELECT count(*) FROM fraud.policy_activations");
        assertSqlState("P0412", () -> cas(stale, before.revision(), ACTOR));
        assertSqlState("P0412", () -> cas(original, before.revision(), ACTOR));
        assertThat(head()).isEqualTo(returned);
        assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_versions WHERE policy_hash=?", stale.hash()))
                .isEqualTo("0");
        assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_activations")).isEqualTo(audits);
    }

    @Test
    void concurrentWritersWithTheSameRevisionHaveOneWinnerAndOneStaleResult() throws Exception {
        var before = head();
        String audits = scalar(API, "SELECT count(*) FROM fraud.policy_activations");
        var first = candidate();
        var second = candidate();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(attempt(first, ACTOR, before.revision(), ready, start));
            var b = executor.submit(attempt(second, OTHER_ACTOR, before.revision(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(result -> result.error() == null)).hasSize(1);
            assertThat(results.stream().filter(result -> "P0412".equals(result.error()))).hasSize(1);
            var winner = results.stream().filter(result -> result.error() == null).findFirst().orElseThrow();
            var loser = results.stream().filter(result -> result.error() != null).findFirst().orElseThrow();
            assertThat(head()).isEqualTo(new Head(before.revision() + 1, winner.policy().hash()));
            assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_activations"))
                    .isEqualTo(Long.toString(Long.parseLong(audits) + 1));
            assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_versions WHERE policy_hash=?", loser.policy().hash()))
                    .isEqualTo("0");
            assertThat(scalar(API, "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?",
                    before.revision() + 1)).isEqualTo(winner.actor());
        } finally {
            start.countDown();
        }
    }

    @Test
    void activationAndItsAuditRollBackWithTheCallingTransaction() throws Exception {
        var before = head();
        var candidate = candidate();
        String audits = scalar(API, "SELECT count(*) FROM fraud.policy_activations");
        try (var connection = connect(API)) {
            connection.setAutoCommit(false);
            assertThat(cas(connection, candidate, before.revision(), ACTOR).hash()).isEqualTo(candidate.hash());
            assertThat(head()).isEqualTo(before);
            connection.rollback();
        }
        assertThat(head()).isEqualTo(before);
        assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_versions WHERE policy_hash=?", candidate.hash()))
                .isEqualTo("0");
        assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_activations")).isEqualTo(audits);
    }

    @Test
    void reactivationKeepsTheOriginalPublisherAndRecordsTheNewActivationActor() throws Exception {
        var original = new Candidate(originalHash, SOURCE);
        var changed = cas(candidate(), head().revision(), ACTOR);
        var after = cas(original, changed.revision(), OTHER_ACTOR);
        assertThat(scalar(API, "SELECT published_principal_id FROM fraud.policy_versions WHERE policy_hash=?", originalHash))
                .isNull();
        assertThat(scalar(API, "SELECT published_by FROM fraud.policy_versions WHERE policy_hash=?", originalHash))
                .isEqualTo(CLI);
        assertThat(scalar(API, "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?", after.revision()))
                .isEqualTo(OTHER_ACTOR);
    }

    @Test
    void cliSeedAndNoOpRemainCompatibleWithoutAttributingAnApplicationPrincipal() throws Exception {
        var before = head();
        var candidate = candidate();
        String audits = scalar(CLI, "SELECT count(*) FROM fraud.policy_activations");
        assertThat(cli(candidate, true)).isEqualTo(before.hash());
        assertThat(head()).isEqualTo(before);
        var current = new Candidate(before.hash(), scalar(CLI,
                "SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash=?", before.hash()));
        assertThat(cli(current, false)).isEqualTo(before.hash());
        assertThat(scalar(CLI, "SELECT count(*) FROM fraud.policy_activations")).isEqualTo(audits);
        assertThat(cli(candidate, false)).isEqualTo(candidate.hash());
        assertThat(head()).isEqualTo(new Head(before.revision() + 1, candidate.hash()));
        assertThat(scalar(CLI, "SELECT published_principal_id FROM fraud.policy_versions WHERE policy_hash=?", candidate.hash()))
                .isNull();
        assertThat(scalar(CLI, "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?", before.revision() + 1))
                .isNull();
        assertThat(scalar(CLI, "SELECT activated_by FROM fraud.policy_activations WHERE revision=?", before.revision() + 1))
                .isEqualTo(CLI);
    }

    @Test
    void cliCanStillSeedAnEmptyHeadWithoutReplacingItOnTheNextSeed() throws Exception {
        // Roll back this synthetic empty-head fixture so other checks retain their history.
        try (var connection = connect(MIGRATOR); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM fraud.policy_activations");
            statement.executeUpdate("UPDATE fraud.policy_head SET policy_hash=NULL,revision=0");
            var first = candidate();
            var second = candidate();
            assertThat(cli(connection, first, true)).isEqualTo(first.hash());
            assertThat(head(connection)).isEqualTo(new Head(1, first.hash()));
            assertThat(cli(connection, second, true)).isEqualTo(first.hash());
            assertThat(head(connection)).isEqualTo(new Head(1, first.hash()));
            assertThat(scalar(connection, "SELECT count(*) FROM fraud.policy_activations")).isEqualTo("1");
            connection.rollback();
        }
    }

    @Test
    void guardedActivationRequiresAnEnabledAdministratorAndNonNullRevision() throws Exception {
        var before = head();
        var candidate = candidate();
        for (var actor : List.of("missing-admin", "existing-reader", "disabled-admin")) {
            assertSqlState("42501", () -> cas(candidate, before.revision(), actor));
        }
        assertSqlState("22023", () -> cas(candidate, -1, ACTOR));
        try (var connection = connect(API)) {
            assertSqlState("22004", () -> scalar(connection,
                    "SELECT policy_hash FROM fraud.activate_policy_if_revision(?,?,NULL,?)",
                    candidate.hash(), candidate.source(), ACTOR));
            assertSqlState("22004", () -> scalar(connection,
                    "SELECT policy_hash FROM fraud.activate_policy_if_revision(?,?,?,NULL)",
                    candidate.hash(), candidate.source(), before.revision()));
        }
        assertThat(head()).isEqualTo(before);
        assertThat(scalar(API, "SELECT count(*) FROM fraud.policy_versions WHERE policy_hash=?", candidate.hash()))
                .isEqualTo("0");
    }

    @Test
    void apiWriterHasOnlyPolicyReadsAndTheGuardedWrapperWithoutDdlOrDirectDml() throws Exception {
        for (var role : List.of(API, CLI, RUNTIME)) {
            assertThat(scalar(MIGRATOR, "SELECT has_function_privilege(?::name,?::text,'EXECUTE')", role, CORE))
                    .isEqualTo("f");
        }
        assertThat(scalar(MIGRATOR, "SELECT has_function_privilege(?::name,?::text,'EXECUTE')", API, GUARDED))
                .isEqualTo("t");
        assertThat(scalar(MIGRATOR, "SELECT has_function_privilege(?::name,?::text,'EXECUTE')", CLI, GUARDED))
                .isEqualTo("f");
        assertThat(scalar(MIGRATOR, "SELECT has_function_privilege(?::name,?::text,'EXECUTE')", RUNTIME, GUARDED))
                .isEqualTo("f");
        for (var command : List.of(
                "UPDATE fraud.policy_head SET revision=revision+1",
                "DELETE FROM fraud.policy_activations",
                "UPDATE fraud.policy_versions SET policy_yaml='changed'",
                "CREATE TABLE fraud.forbidden_policy_api(id integer)",
                "CREATE TABLE public.forbidden_policy_api(id integer)",
                "SET ROLE fraud_migrator",
                "SELECT * FROM fraud.api_credentials",
                "SELECT * FROM fraud.api_principals",
                "SELECT fraud.activate_policy(repeat('a',64),'source',false)",
                "SELECT * FROM fraud._activate_policy(repeat('a',64),'source',false,1,'policy-admin-a')")) {
            assertSqlState("42501", () -> execute(API, command));
        }
    }

    @Test
    void principalRoleConstraintAddsAdministratorWhileKeepingExistingBoundsAndNullChecks() throws Exception {
        insertPrincipal("all-four-roles", true, "INGEST", "READ", "POLICY", "POLICY_ADMIN");
        assertSqlState("23514", () -> insertPrincipal("no-roles", true));
        assertSqlState("23514", () -> insertPrincipal("unknown-role", true, "ROOT"));
        assertSqlState("23514", () -> insertPrincipal("null-role", true, (String) null));
        assertSqlState("23514", () -> insertPrincipal("too-many-roles", true,
                "INGEST", "READ", "POLICY", "POLICY_ADMIN", "READ"));
        assertSqlState("23514", () -> execute(MIGRATOR,
                "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) "
                        + "VALUES ('multidimensional','policy-admin',true,'{{INGEST,READ},{POLICY,POLICY_ADMIN}}'::text[],ARRAY[]::text[])"));
    }

    private static Callable<Attempt> attempt(Candidate policy, String actor, long revision,
            CountDownLatch ready, CountDownLatch start) {
        return () -> {
            try (var connection = connect(API)) {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent writer was not released");
                try { return new Attempt(policy, actor, cas(connection, policy, revision, actor), null); }
                catch (SQLException failure) {
                    if (!"P0412".equals(failure.getSQLState())) throw failure;
                    return new Attempt(policy, actor, null, failure.getSQLState());
                }
            }
        };
    }

    private static Candidate candidate() {
        String amount = BigDecimal.valueOf(2_500_000L + CANDIDATES.incrementAndGet(), 2).toPlainString();
        String source = SOURCE.replace("25000.00", amount);
        return new Candidate(LOADER.read(source).policyHash(), source);
    }

    private static Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), role, TestDatabase.PASSWORD);
    }

    private static Head head() throws SQLException {
        try (var connection = connect(RUNTIME)) { return head(connection); }
    }

    private static Head head(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT revision,policy_hash FROM fraud.policy_head WHERE singleton")) {
            assertThat(rows.next()).isTrue();
            return new Head(rows.getLong(1), rows.getString(2));
        }
    }

    private static Head cas(Candidate policy, long revision, String actor) throws SQLException {
        try (var connection = connect(API)) { return cas(connection, policy, revision, actor); }
    }

    private static Head cas(Connection connection, Candidate policy, long revision, String actor) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision,policy_hash FROM fraud.activate_policy_if_revision(?,?,?,?)")) {
            statement.setString(1, policy.hash()); statement.setString(2, policy.source());
            statement.setLong(3, revision); statement.setString(4, actor);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                var result = new Head(rows.getLong(1), rows.getString(2));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    private static String cli(Candidate policy, boolean seed) throws SQLException {
        try (var connection = connect(CLI)) { return cli(connection, policy, seed); }
    }

    private static String cli(Connection connection, Candidate policy, boolean seed) throws SQLException {
        return scalar(connection, "SELECT fraud.activate_policy(?,?,?)", policy.hash(), policy.source(), seed);
    }

    private static String scalar(String role, String sql, Object... values) throws SQLException {
        try (var connection = connect(role)) { return scalar(connection, sql, values); }
    }

    private static String scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static String version(String hash) throws SQLException {
        return scalar(RUNTIME,
                "SELECT (to_jsonb(v)-'published_principal_id')::text FROM fraud.policy_versions v WHERE policy_hash=?", hash);
    }

    private static String activation(long revision) throws SQLException {
        return scalar(RUNTIME,
                "SELECT (to_jsonb(a)-'activated_principal_id')::text FROM fraud.policy_activations a WHERE revision=?", revision);
    }

    private static String runtimeGrants() throws SQLException {
        return scalar(MIGRATOR,
                "SELECT jsonb_agg(to_jsonb(g) ORDER BY table_name,privilege_type)::text "
                        + "FROM (SELECT table_name,privilege_type FROM information_schema.role_table_grants "
                        + "WHERE table_schema='fraud' AND grantee='fraud_runtime') g");
    }

    private static void insertPrincipal(String id, boolean enabled, String... roles) throws SQLException {
        try (var connection = connect(MIGRATOR);
                var statement = connection.prepareStatement(
                        "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources) "
                                + "VALUES (?,'policy-admin',?,?,ARRAY[]::text[])")) {
            statement.setString(1, id); statement.setBoolean(2, enabled);
            statement.setArray(3, connection.createArrayOf("text", roles));
            statement.executeUpdate();
        }
    }

    private static void execute(String role, String sql) throws SQLException {
        try (var connection = connect(role); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static void assertSqlState(String expected, SqlWork operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).isEqualTo(expected));
    }

    @FunctionalInterface private interface SqlWork { void run() throws SQLException; }
    private record Candidate(String hash, String source) { }
    private record Head(long revision, String hash) { }
    private record Attempt(Candidate policy, String actor, Head result, String error) { }
}
