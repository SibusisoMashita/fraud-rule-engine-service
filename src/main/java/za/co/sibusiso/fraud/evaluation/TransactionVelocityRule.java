package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.Velocity;
import za.co.sibusiso.fraud.configuration.BuiltInRules;
import za.co.sibusiso.fraud.configuration.RuleDefinition;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;

public final class TransactionVelocityRule implements FraudRule<Velocity> {
    @Override public RuleDefinition<Velocity> definition() { return BuiltInRules.velocity(); }
    @Override public int maximumEvidenceFacts() { return 5; } // Versioned evidence shape.
    @Override public Set<HistoryQuery> historyQueries(TransactionEvent event, Velocity settings) {
        return Set.of(new HistoryQuery.Count(settings.windowSeconds()));
    }
    @Override public RuleResult evaluate(TransactionEvent event, Velocity settings, Context context) {
        long count = Math.addExact(context.history().required(new HistoryQuery.Count(settings.windowSeconds())).longValueExact(), 1);
        boolean match = count > settings.maxTransactions();
        return new RuleResult(definition().id(), definition().algorithmVersion(),
                match ? RuleResult.Status.MATCH : RuleResult.Status.NO_MATCH,
                match ? "TRANSACTION_COUNT_EXCEEDS_LIMIT" : "TRANSACTION_COUNT_WITHIN_LIMIT", Map.of(
                        RuleResult.EVIDENCE_SCHEMA_VERSION_KEY, new RuleResult.Number(BigDecimal.ONE),
                        "count", new RuleResult.Number(BigDecimal.valueOf(count)),
                        "maxTransactions", new RuleResult.Number(BigDecimal.valueOf(settings.maxTransactions())),
                        "windowSeconds", new RuleResult.Number(BigDecimal.valueOf(settings.windowSeconds())),
                        "contextHighWatermark", new RuleResult.Number(BigDecimal.valueOf(context.highWatermark()))));
    }
}
