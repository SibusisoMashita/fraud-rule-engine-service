package za.co.sibusiso.fraud.policy.management;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;

/** Owns its restricted connection pool without replacing the runtime datasource. */
public final class JdbcPolicyWriter implements AutoCloseable {
    public record Activation(long revision, String policyHash, String document) {}

    private record Reference(long revision, String policyHash) {}

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcPolicyWriter(HikariDataSource pool) {
        this.pool = pool;
        this.jdbc = new JdbcTemplate(pool);
        // Keep transaction-completion failures distinct from rolled-back SQL failures.
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    public <T> T activate(
            String hash,
            String document,
            long revision,
            String principalId,
            Function<Activation, T> response) {
        return transactions.execute(
                status -> {
                    // Keep the head lock until the stored response has been read and verified.
                    var activated =
                            jdbc.queryForObject(
                                    "SELECT revision, policy_hash FROM fraud.activate_policy_if_revision(?, ?, ?, ?)",
                                    (row, index) ->
                                            new Reference(
                                                    row.getLong("revision"),
                                                    row.getString("policy_hash")),
                                    hash,
                                    document,
                                    revision,
                                    principalId);
                    var stored =
                            jdbc.queryForObject(
                                    "SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash = ?",
                                    String.class,
                                    activated.policyHash());
                    return response.apply(
                            new Activation(activated.revision(), activated.policyHash(), stored));
                });
    }

    @Override
    public void close() {
        pool.close();
    }
}
