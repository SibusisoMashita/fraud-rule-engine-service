package za.co.sibusiso.fraud.policy.management;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/** Owns its restricted connection pool without replacing the runtime datasource. */
public final class JdbcPolicyWriter implements AutoCloseable {
    public record Activation(long revision, String policyHash) {}

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    public JdbcPolicyWriter(HikariDataSource pool) {
        this.pool = pool;
        this.jdbc = new JdbcTemplate(pool);
    }

    public Activation activate(String hash, String document, long revision, String principalId) {
        // The database function owns the lock, revision check and complete audit write.
        return jdbc.queryForObject(
                "SELECT revision, policy_hash FROM fraud.activate_policy_if_revision(?, ?, ?, ?)",
                (row, index) ->
                        new Activation(row.getLong("revision"), row.getString("policy_hash")),
                hash,
                document,
                revision,
                principalId);
    }

    @Override
    public void close() {
        pool.close();
    }
}
