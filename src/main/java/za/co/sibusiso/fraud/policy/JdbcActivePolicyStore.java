package za.co.sibusiso.fraud.policy;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!migrate & !policy-admin")
public class JdbcActivePolicyStore implements ActivePolicyStore {
    private final JdbcTemplate jdbc;

    public JdbcActivePolicyStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Reference active() {
        return jdbc.queryForObject("SELECT revision, policy_hash FROM fraud.policy_head WHERE singleton AND policy_hash IS NOT NULL",
                (row, index) -> new Reference(row.getLong("revision"), row.getString("policy_hash")));
    }

    @Override
    public String document(String policyHash) {
        return jdbc.queryForObject("SELECT policy_yaml FROM fraud.policy_versions WHERE policy_hash = ?", String.class, policyHash);
    }
}
