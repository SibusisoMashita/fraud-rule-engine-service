package za.co.sibusiso.fraud.evaluation.rules;

import java.util.Objects;

/** A checked evaluation failure whose original cause remains available for diagnostics. */
public final class RuleEvaluationException extends Exception {
    public RuleEvaluationException(Throwable cause) {
        super("Rule evaluation failed", Objects.requireNonNull(cause));
    }
}
