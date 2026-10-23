package za.co.sibusiso.fraud.evaluation.rules;

import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.policy.definition.BuiltInRuleSettings.HighAmount;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.RuleDefinition;

import java.math.BigDecimal;
import java.util.Map;

public final class HighAmountRule implements FraudRule<HighAmount> {
    private final RuleDefinition<HighAmount> definition = BuiltInRules.highAmount();

    @Override
    public int maximumEvidenceFacts() {
        return 4;
    } // Fixed evidence schema, not a fraud threshold.

    @Override
    public RuleDefinition<HighAmount> definition() {
        return definition;
    }

    @Override
    public RuleResult evaluate(TransactionEvent event, HighAmount settings, Context context)
            throws RuleEvaluationException {
        BigDecimal threshold = settings.thresholds().get(event.money().currency()).value();
        boolean match = event.money().amount().compareTo(threshold) > 0;
        return new RuleResult(
                definition.id(),
                definition.algorithmVersion(),
                match ? RuleResult.Status.MATCH : RuleResult.Status.NO_MATCH,
                match ? "AMOUNT_EXCEEDS_THRESHOLD" : "AMOUNT_WITHIN_THRESHOLD",
                Map.of(
                        RuleResult.EVIDENCE_SCHEMA_VERSION_KEY,
                        new RuleResult.Number(BigDecimal.ONE),
                        "amount",
                        new RuleResult.Number(event.money().amount()),
                        "threshold",
                        new RuleResult.Number(threshold),
                        "currency",
                        new RuleResult.Text(event.money().currency())));
    }
}
