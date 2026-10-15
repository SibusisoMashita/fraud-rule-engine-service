package za.co.sibusiso.fraud.evaluation;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import za.co.sibusiso.fraud.configuration.ServiceLimits;

final class WorkBudget {
    private final LongSupplier requestRemaining;
    private final ServiceLimits.Operations operations;
    private final long expiresAt;
    WorkBudget(LongSupplier requestRemaining, ServiceLimits.Operations operations) {
        this.requestRemaining = requestRemaining; this.operations = operations;
        expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(operations.maxProcessingMillis());
    }
    int remaining() {
        long work = TimeUnit.NANOSECONDS.toMillis(expiresAt - System.nanoTime());
        long available = Math.min(work, requestRemaining.getAsLong() - operations.commitReserveMillis());
        if (available <= 0) throw new FlowFailure(503, "DEADLINE_EXCEEDED");
        return (int) available;
    }
    int commitMillis() {
        remaining();
        long available = requestRemaining.getAsLong();
        if (available <= 0) throw new FlowFailure(503, "DEADLINE_EXCEEDED");
        return (int) Math.min(available, operations.commitReserveMillis());
    }
    int statementMillis() { return Math.min(remaining(), operations.statementTimeoutMillis()); }
    int lockMillis() { return Math.min(remaining(), operations.lockTimeoutMillis()); }
}
