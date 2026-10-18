package za.co.sibusiso.fraud.ingress.security;

import org.springframework.http.HttpMethod;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.ingress.ApiPaths;

public enum IngressRoute {
    INGEST(Caller.Role.INGEST),
    READ(Caller.Role.READ),
    POLICY(Caller.Role.POLICY),
    POLICY_ADMIN(Caller.Role.POLICY_ADMIN);
    private final Caller.Role role;

    IngressRoute(Caller.Role role) {
        this.role = role;
    }

    public Caller.Role role() {
        return role;
    }

    public ServiceLimits.Bucket bucket(ServiceLimits limits) {
        return switch (this) {
            case INGEST -> limits.api().rateLimits().ingest();
            case READ -> limits.api().rateLimits().read();
            case POLICY, POLICY_ADMIN -> limits.api().rateLimits().policy();
        };
    }

    public static IngressRoute resolve(String method, String path) {
        return resolve(method, path, false);
    }

    public static IngressRoute resolve(String method, String path, boolean policyManagementEnabled) {
        if (policyManagementEnabled
                && ((path.equals(ApiPaths.ADMIN_POLICY)
                                && (HttpMethod.GET.matches(method) || HttpMethod.PUT.matches(method)))
                        || (path.equals(ApiPaths.ADMIN_POLICY_VALIDATE)
                                && HttpMethod.POST.matches(method)))) {
            return POLICY_ADMIN;
        }
        if (HttpMethod.POST.matches(method) && path.equals(ApiPaths.TRANSACTIONS)) return INGEST;
        if (!HttpMethod.GET.matches(method)) return null;
        if (path.equals(ApiPaths.RULES)) return POLICY;
        if (path.equals(ApiPaths.EVALUATIONS)
                || path.matches(ApiPaths.TRANSACTIONS + "/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            return READ;
        return null;
    }
}
