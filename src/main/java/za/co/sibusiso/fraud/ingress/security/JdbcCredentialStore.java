package za.co.sibusiso.fraud.ingress.security;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource;

@Repository
@Profile("!migrate & !policy-admin")
public class JdbcCredentialStore implements CredentialStore {
    private final JdbcTemplate jdbc;
    public JdbcCredentialStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Credential> active(String keyId) {
        var matches = jdbc.query("""
                SELECT c.secret_hash, p.principal_id, p.scope, p.roles, p.location_sources, p.permission_version
                FROM fraud.api_credentials c JOIN fraud.api_principals p USING (principal_id)
                WHERE c.key_id = ? AND p.enabled AND NOT c.revoked
                  AND c.valid_from <= clock_timestamp() AND c.expires_at > clock_timestamp()
                """, (row, index) -> new Credential(row.getString("secret_hash"), new Caller(
                        row.getString("principal_id"), row.getString("scope"),
                        strings(row.getArray("roles")).stream().map(Caller.Role::valueOf).collect(Collectors.toSet()),
                        strings(row.getArray("location_sources")).stream().map(LocationSource::valueOf).collect(Collectors.toSet()),
                        row.getLong("permission_version"))), keyId);
        return matches.stream().findFirst();
    }
    private static Set<String> strings(java.sql.Array value) throws java.sql.SQLException {
        try { return Set.copyOf(Arrays.asList((String[]) value.getArray())); }
        finally { value.free(); }
    }
}
