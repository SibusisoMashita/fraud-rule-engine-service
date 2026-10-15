package za.co.sibusiso.fraud.ingress;

import java.util.concurrent.TimeUnit;

/** Cooperative work budget; socket and edge read timeouts bound network waits separately. */
public final class RequestDeadline {
    public static final String ATTRIBUTE = RequestDeadline.class.getName();
    private final long expiresAt;
    public RequestDeadline(int millis) { expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    public void check() {
        if (System.nanoTime() - expiresAt >= 0) throw new ApiFailure(503, "DEADLINE_EXCEEDED", 1);
    }
}
