package za.co.sibusiso.fraud.ingress.security;

import java.security.Principal;
import java.util.Set;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource;

public record Caller(String principalId, String scope, Set<Role> roles, Set<LocationSource> locationSources,
        long permissionVersion) implements Principal {
    /** POLICY_ADMIN grants global policy administration, independently of transaction scope. */
    public enum Role { INGEST, READ, POLICY, POLICY_ADMIN }
    public Caller {
        principalId = ContractChecks.identifier(principalId);
        scope = ContractChecks.identifier(scope);
        roles = Set.copyOf(roles);
        locationSources = Set.copyOf(locationSources);
        ContractChecks.require(!roles.isEmpty() && permissionVersion > 0, "Invalid principal permissions");
    }
    @Override public String getName() { return principalId; }
}
