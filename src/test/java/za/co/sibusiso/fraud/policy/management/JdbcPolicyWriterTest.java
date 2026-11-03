package za.co.sibusiso.fraud.policy.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

@Testcontainers
class JdbcPolicyWriterTest {
    @Container
    static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(
                            DockerImageName.parse(TestImages.POSTGRES)
                                    .asCompatibleSubstituteFor("postgres"))
                    .withEnv("POSTGRES_INITDB_ARGS", TestImages.INITDB_ARGS);

    private static final String ACTOR = "atomic-policy-administrator";
    private static final AtomicInteger CANDIDATES = new AtomicInteger();
    private static final StrictYaml YAML = new StrictYaml();
    private static final PolicyLoader LOADER =
            new PolicyLoader(YAML, RuleRegistry.of(BuiltInRules.definitions(), 64));
    private static final String ORIGINAL =
            YAML.readText(new ClassPathResource("config/policy.example.yaml"));
    private static JdbcTemplate observer;

    @BeforeAll
    static void initializeDatabase() throws SQLException {
        TestDatabase.initialize(DATABASE);
        observer = database("fraud_runtime");
        database("fraud_migrator")
                .update(
                        "INSERT INTO fraud.api_principals(principal_id,scope,enabled,roles,location_sources)"
                                + " VALUES (?,'policy-admin',true,ARRAY['POLICY_ADMIN'],ARRAY[]::text[])",
                        ACTOR);
    }

    @Test
    void preparedResponseAndItsStoredDocumentAreInvisibleUntilTheActivationCommits() {
        var candidate = candidate();
        var before = state();
        try (var writer = new JdbcPolicyWriter(pool())) {
            var result =
                    writer.activate(
                            candidate.hash(),
                            candidate.document(),
                            before.revision(),
                            ACTOR,
                            activation -> {
                                assertThat(activation.revision()).isEqualTo(before.revision() + 1);
                                assertThat(activation.policyHash()).isEqualTo(candidate.hash());
                                assertThat(activation.document()).isEqualTo(candidate.document());
                                assertThat(state())
                                        .as("Uncommitted state is not observable")
                                        .isEqualTo(before);
                                return activation;
                            });
            assertCommitted(before, candidate, result);
        }
    }

    @Test
    void sameHashNoOpReturnsTheOriginalStoredRepresentationWithoutChangingAudit() {
        var candidate = candidate();
        try (var writer = new JdbcPolicyWriter(pool())) {
            var first =
                    writer.activate(
                            candidate.hash(),
                            candidate.document(),
                            state().revision(),
                            ACTOR,
                            activation -> activation);
            var before = state();
            var result =
                    writer.activate(
                            candidate.hash(),
                            candidate.document() + "\n# equivalent input\n",
                            before.revision(),
                            ACTOR,
                            activation -> activation);
            assertThat(result).isEqualTo(first);
            assertThat(state()).isEqualTo(before);
        }
    }

    @Test
    void responseVerificationFailureRollsBackTheVersionHeadAndAuditTogether() {
        var candidate = candidate();
        var before = state();
        var failure = new IllegalStateException("synthetic document verification failure");
        try (var writer = new JdbcPolicyWriter(pool())) {
            assertThatThrownBy(
                            () ->
                                    writer.activate(
                                            candidate.hash(),
                                            candidate.document(),
                                            before.revision(),
                                            ACTOR,
                                            activation -> {
                                                throw failure;
                                            }))
                    .isSameAs(failure);
            assertThat(state()).isEqualTo(before);
        }
    }

    @Test
    void storedDocumentReadFailureRollsBackTheActivationBeforeAnyResponseIsPrepared()
            throws SQLException {
        var candidate = candidate();
        var before = state();
        var failure = new SQLException("synthetic read failure", "08006");
        try (var pool = pool();
                var writer = faultedWriter(pool, failure, Fault.READ)) {
            assertThatThrownBy(
                            () ->
                                    writer.activate(
                                            candidate.hash(),
                                            candidate.document(),
                                            before.revision(),
                                            ACTOR,
                                            activation -> {
                                                throw new AssertionError(
                                                        "A failed read cannot prepare a response");
                                            }))
                    .isInstanceOf(DataAccessException.class)
                    .hasRootCause(failure);
            assertThat(state()).isEqualTo(before);
        }
    }

    @Test
    void staleRevisionCannotPublishTheCandidateOrPrepareAResponse() {
        var candidate = candidate();
        var before = state();
        try (var writer = new JdbcPolicyWriter(pool())) {
            assertThatThrownBy(
                            () ->
                                    writer.activate(
                                            candidate.hash(),
                                            candidate.document(),
                                            before.revision() - 1,
                                            ACTOR,
                                            activation -> {
                                                throw new AssertionError(
                                                        "A stale candidate cannot prepare a response");
                                            }))
                    .isInstanceOfSatisfying(
                            DataAccessException.class,
                            failure ->
                                    assertThat(
                                                    ((SQLException) failure.getRootCause())
                                                            .getSQLState())
                                            .isEqualTo("P0412"));
            assertThat(state()).isEqualTo(before);
        }
    }

    @Test
    void lostCommitAcknowledgementRetainsItsCauseEvenWhenTheActivationWasCommitted()
            throws SQLException {
        var candidate = candidate();
        var before = state();
        var failure = new SQLException("synthetic lost commit acknowledgement", "08006");
        try (var pool = pool();
                var writer = faultedWriter(pool, failure, Fault.COMMIT)) {
            assertThatThrownBy(
                            () ->
                                    writer.activate(
                                            candidate.hash(),
                                            candidate.document(),
                                            before.revision(),
                                            ACTOR,
                                            activation -> activation))
                    .isInstanceOf(TransactionSystemException.class)
                    .hasCause(failure);
            assertCommitted(
                    before,
                    candidate,
                    new JdbcPolicyWriter.Activation(
                            before.revision() + 1, candidate.hash(), candidate.document()));
        }
    }

    @Test
    void lostRollbackAcknowledgementPreservesTheCompletionAndOriginalReadFailures()
            throws SQLException {
        var candidate = candidate();
        var before = state();
        var failure = new SQLException("synthetic lost rollback acknowledgement", "08006");
        try (var pool = pool();
                var writer = faultedWriter(pool, failure, Fault.ROLLBACK)) {
            assertThatThrownBy(
                            () ->
                                    writer.activate(
                                            candidate.hash(),
                                            candidate.document(),
                                            before.revision(),
                                            ACTOR,
                                            activation -> {
                                                throw new AssertionError(
                                                        "A failed read cannot prepare a response");
                                            }))
                    .isInstanceOfSatisfying(
                            TransactionSystemException.class,
                            completion -> {
                                assertThat(completion).hasCause(failure);
                                assertThat(completion.getApplicationException())
                                        .isInstanceOf(DataAccessException.class)
                                        .hasRootCauseMessage("synthetic read before rollback");
                            });
            assertThat(state()).isEqualTo(before);
        }
    }

    private static JdbcPolicyWriter faultedWriter(
            HikariDataSource actual, SQLException failure, Fault fault) throws SQLException {
        var wrapped = mock(HikariDataSource.class);
        when(wrapped.getConnection())
                .thenAnswer(
                        ignored -> {
                            var connection = actual.getConnection();
                            return Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (proxy, method, arguments) -> {
                                        if (fault != Fault.COMMIT
                                                && method.getName().equals("prepareStatement")
                                                && ((String) arguments[0]).contains("policy_yaml"))
                                            throw fault == Fault.READ
                                                    ? failure
                                                    : new SQLException(
                                                            "synthetic read before rollback",
                                                            "08006");
                                        try {
                                            var result = method.invoke(connection, arguments);
                                            if ((fault == Fault.COMMIT
                                                            && method.getName().equals("commit"))
                                                    || (fault == Fault.ROLLBACK
                                                            && method.getName().equals("rollback")))
                                                throw failure;
                                            return result;
                                        } catch (InvocationTargetException thrown) {
                                            throw thrown.getCause();
                                        }
                                    });
                        });
        return new JdbcPolicyWriter(wrapped);
    }

    private static void assertCommitted(
            State before, Candidate candidate, JdbcPolicyWriter.Activation result) {
        assertThat(state().revision()).isEqualTo(result.revision());
        assertThat(state().hash()).isEqualTo(result.policyHash());
        assertThat(state().records()).isNotEqualTo(before.records());
        assertThat(
                        observer.queryForObject(
                                "SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash=?",
                                String.class,
                                candidate.hash()))
                .isEqualTo(candidate.document());
        assertThat(
                        observer.queryForObject(
                                "SELECT activated_principal_id FROM fraud.policy_activations WHERE revision=?",
                                String.class,
                                result.revision()))
                .isEqualTo(ACTOR);
    }

    private static State state() {
        return observer.queryForObject(
                "SELECT h.revision,h.policy_hash,jsonb_build_object('head',to_jsonb(h),"
                        + "'versions',(SELECT jsonb_agg(to_jsonb(v) ORDER BY v.policy_hash)"
                        + " FROM fraud.policy_versions v),"
                        + "'activations',(SELECT jsonb_agg(to_jsonb(a) ORDER BY a.revision)"
                        + " FROM fraud.policy_activations a))::text AS records"
                        + " FROM fraud.policy_head h WHERE h.singleton",
                (row, index) ->
                        new State(
                                row.getLong("revision"),
                                row.getString("policy_hash"),
                                row.getString("records")));
    }

    private static Candidate candidate() {
        String amount =
                BigDecimal.valueOf(2_500_000L + CANDIDATES.incrementAndGet(), 2).toPlainString();
        String document = ORIGINAL.replace("25000.00", amount);
        return new Candidate(LOADER.read(document).policyHash(), document);
    }

    private static JdbcTemplate database(String role) {
        return new JdbcTemplate(
                new DriverManagerDataSource(DATABASE.getJdbcUrl(), role, TestDatabase.PASSWORD));
    }

    private static HikariDataSource pool() {
        var configuration = new HikariConfig();
        configuration.setJdbcUrl(DATABASE.getJdbcUrl());
        configuration.setUsername("fraud_policy_api");
        configuration.setPassword(TestDatabase.PASSWORD);
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        return new HikariDataSource(configuration);
    }

    private record Candidate(String hash, String document) {}

    private enum Fault {
        READ,
        COMMIT,
        ROLLBACK
    }

    private record State(long revision, String hash, String records) {}
}
