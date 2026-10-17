package za.co.sibusiso.fraud.ingress.error;

import org.springframework.http.HttpStatus;
import za.co.sibusiso.fraud.contracts.ErrorCode;

public final class ApiFailure extends RuntimeException {
    public static final long DEFAULT_RETRY_AFTER_SECONDS = 1;

    public enum Outcome {
        NOT_ACCEPTED,
        UNKNOWN
    }

    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;
    private final Outcome outcome;

    public ApiFailure(HttpStatus status, ErrorCode code) {
        this(status, code, 0);
    }

    public ApiFailure(HttpStatus status, ErrorCode code, long retryAfterSeconds) {
        this(status, code, retryAfterSeconds, Outcome.NOT_ACCEPTED);
    }

    public ApiFailure(HttpStatus status, ErrorCode code, long retryAfterSeconds, Outcome outcome) {
        this(status, code.name(), retryAfterSeconds, outcome);
    }

    public ApiFailure(HttpStatus status, ErrorCode code, long retryAfterSeconds, Throwable cause) {
        this(status, code.name(), retryAfterSeconds, Outcome.NOT_ACCEPTED, cause);
    }

    public ApiFailure(
            HttpStatus status,
            ErrorCode code,
            long retryAfterSeconds,
            Outcome outcome,
            Throwable cause) {
        this(status, code.name(), retryAfterSeconds, outcome, cause);
    }

    public ApiFailure(HttpStatus status, String code, long retryAfterSeconds, Outcome outcome) {
        this(status, code, retryAfterSeconds, outcome, null);
    }

    public ApiFailure(
            HttpStatus status,
            String code,
            long retryAfterSeconds,
            Outcome outcome,
            Throwable cause) {
        super(code, cause);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.outcome = outcome;
    }

    public String outcome() {
        return outcome.name();
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
