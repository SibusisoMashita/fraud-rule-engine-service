package za.co.sibusiso.fraud.ingress;

public final class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfterSeconds;

    public ApiFailure(int status, String code) { this(status, code, 0); }
    public ApiFailure(int status, String code, long retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public int status() { return status; }
    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
