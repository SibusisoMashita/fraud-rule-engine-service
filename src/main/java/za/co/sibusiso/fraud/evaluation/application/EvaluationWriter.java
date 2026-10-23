package za.co.sibusiso.fraud.evaluation.application;

import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.ingress.security.Caller;

import java.util.function.LongSupplier;

public interface EvaluationWriter {
    final class Attempt {
        private boolean commitReady;

        public void readyToCommit() {
            commitReady = true;
        }

        public boolean isCommitReady() {
            return commitReady;
        }
    }

    record Submission(boolean created, TransactionStore.Stored stored) {}

    Submission write(
            Caller caller,
            TransactionEvent event,
            String requestHash,
            LongSupplier remainingMillis,
            Attempt attempt)
            throws Exception;
}
