package za.co.sibusiso.fraud.ingress.request;

import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;

/** Cooperative work budget; socket and edge read timeouts bound network waits separately. */
public final class RequestDeadline {
    public static final String ATTRIBUTE = RequestDeadline.class.getName();
    private final long expiresAt;

    public RequestDeadline(int millis) {
        expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    public long remainingMillis() {
        long nanos = expiresAt - System.nanoTime();
        return nanos <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    public void check() {
        if (System.nanoTime() - expiresAt >= 0) {
            throw new ApiFailure(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.DEADLINE_EXCEEDED,
                    ApiFailure.DEFAULT_RETRY_AFTER_SECONDS);
        }
    }
}
