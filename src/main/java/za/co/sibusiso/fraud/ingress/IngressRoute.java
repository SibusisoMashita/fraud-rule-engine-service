package za.co.sibusiso.fraud.ingress;

import za.co.sibusiso.fraud.configuration.ServiceLimits;

public enum IngressRoute {
    INGEST(Caller.Role.INGEST), READ(Caller.Role.READ), POLICY(Caller.Role.POLICY);
    private final Caller.Role role;
    IngressRoute(Caller.Role role) { this.role = role; }
    public Caller.Role role() { return role; }
    public ServiceLimits.Bucket bucket(ServiceLimits limits) {
        return switch (this) {
            case INGEST -> limits.api().rateLimits().ingest();
            case READ -> limits.api().rateLimits().read();
            case POLICY -> limits.api().rateLimits().policy();
        };
    }
    public static IngressRoute resolve(String method, String path) {
        if (method.equals("POST") && path.equals("/api/v1/transactions")) return INGEST;
        if (!method.equals("GET")) return null;
        if (path.equals("/api/v1/rules")) return POLICY;
        if (path.equals("/api/v1/evaluations") || path.matches("/api/v1/transactions/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) return READ;
        return null;
    }
}
