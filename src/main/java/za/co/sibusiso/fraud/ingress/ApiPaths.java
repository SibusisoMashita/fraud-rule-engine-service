package za.co.sibusiso.fraud.ingress;

/** Routes shared by controller mappings and ingress security. */
public final class ApiPaths {
    public static final String TRANSACTIONS = "/api/v1/transactions";
    public static final String EVALUATIONS = "/api/v1/evaluations";
    public static final String RULES = "/api/v1/rules";
    public static final String ADMIN_POLICY = "/api/v1/admin/policy";
    public static final String ADMIN_POLICY_VALIDATE = ADMIN_POLICY + "/validate";
    public static final String LIVE = "/health/live";
    public static final String READY = "/health/ready";

    private ApiPaths() {}
}
