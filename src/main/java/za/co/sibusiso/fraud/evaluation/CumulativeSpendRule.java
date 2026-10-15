package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.CumulativeSpend;
import za.co.sibusiso.fraud.configuration.BuiltInRules;
import za.co.sibusiso.fraud.configuration.RuleDefinition;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;

public final class CumulativeSpendRule implements FraudRule<CumulativeSpend> {
    @Override public RuleDefinition<CumulativeSpend> definition() { return BuiltInRules.cumulativeSpend(); }
    @Override public int maximumEvidenceFacts() { return 6; } // Versioned evidence shape.
    @Override public Set<HistoryQuery> historyQueries(TransactionEvent event, CumulativeSpend settings) {
        return Set.of(new HistoryQuery.Spend(settings.windowSeconds(), event.money().currency()));
    }
    @Override public RuleResult evaluate(TransactionEvent event, CumulativeSpend settings, Context context) {
        BigDecimal total = context.history().required(new HistoryQuery.Spend(settings.windowSeconds(), event.money().currency()))
                .add(event.money().amount());
        BigDecimal threshold = settings.thresholds().get(event.money().currency()).value();
        boolean match = total.compareTo(threshold) > 0;
        return new RuleResult(definition().id(), definition().algorithmVersion(),
                match ? RuleResult.Status.MATCH : RuleResult.Status.NO_MATCH,
                match ? "CUMULATIVE_SPEND_EXCEEDS_THRESHOLD" : "CUMULATIVE_SPEND_WITHIN_THRESHOLD", Map.of(
                        "evidenceSchemaVersion", new RuleResult.Number(BigDecimal.ONE),
                        "total", new RuleResult.Number(total),
                        "threshold", new RuleResult.Number(threshold),
                        "currency", new RuleResult.Text(event.money().currency()),
                        "windowSeconds", new RuleResult.Number(BigDecimal.valueOf(settings.windowSeconds())),
                        "contextHighWatermark", new RuleResult.Number(BigDecimal.valueOf(context.highWatermark()))));
    }
}
