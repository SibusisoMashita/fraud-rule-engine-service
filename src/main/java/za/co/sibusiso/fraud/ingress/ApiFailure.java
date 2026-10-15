package za.co.sibusiso.fraud.ingress;

public final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfterSeconds;
    private final String outcome;

    public ApiFailure(int status, String code) { this(status, code, 0); }
    public ApiFailure(int status, String code, long retryAfterSeconds) {
        this(status, code, retryAfterSeconds, "NOT_ACCEPTED");
    }
    public ApiFailure(int status, String code, long retryAfterSeconds, String outcome) {
        super(code);
        this.outcome = outcome;
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public String outcome() { return outcome; }
    public int status() { return status; }
    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
