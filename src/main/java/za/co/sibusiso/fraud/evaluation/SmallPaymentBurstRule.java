package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Optional;
import za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.SmallPaymentBurst;
import za.co.sibusiso.fraud.configuration.BuiltInRules;
import za.co.sibusiso.fraud.configuration.RuleDefinition;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;

public final class SmallPaymentBurstRule implements FraudRule<SmallPaymentBurst> {
    @Override public RuleDefinition<SmallPaymentBurst> definition() { return BuiltInRules.smallPaymentBurst(); }
    @Override public int maximumEvidenceFacts() { return 10; } // Versioned evidence shape.
    @Override public Optional<SmallPaymentsQuery> smallPaymentsQuery(TransactionEvent event, SmallPaymentBurst settings) {
        return isLarge(event, settings) ? Optional.of(query(event, settings)) : Optional.empty();
    }
    private boolean isLarge(TransactionEvent event, SmallPaymentBurst settings) {
        return event.money().amount().compareTo(settings.largePaymentThreshold().get(event.money().currency()).value()) > 0;
    }
    private SmallPaymentsQuery query(TransactionEvent event, SmallPaymentBurst settings) {
        return new SmallPaymentsQuery(event.occurredAt(), settings.windowSeconds(), event.money().currency(),
                settings.smallPaymentMaximum().get(event.money().currency()).value());
    }
    @Override public RuleResult evaluate(TransactionEvent event, SmallPaymentBurst settings, Context context) {
        var facts = new LinkedHashMap<String, RuleResult.Fact>();
        String currency = event.money().currency();
        facts.put("evidenceSchemaVersion", new RuleResult.Number(BigDecimal.ONE));
        facts.put("amount", new RuleResult.Number(event.money().amount()));
        facts.put("currency", new RuleResult.Text(currency));
        facts.put("smallPaymentMaximum", new RuleResult.Number(settings.smallPaymentMaximum().get(currency).value()));
        facts.put("largePaymentThreshold", new RuleResult.Number(settings.largePaymentThreshold().get(currency).value()));
        facts.put("minSmallPayments", new RuleResult.Number(BigDecimal.valueOf(settings.minSmallPayments())));
        facts.put("windowSeconds", new RuleResult.Number(BigDecimal.valueOf(settings.windowSeconds())));
        facts.put("contextHighWatermark", new RuleResult.Number(BigDecimal.valueOf(context.highWatermark())));
        boolean match = false;
        String reason = "AMOUNT_NOT_LARGE";
        if (isLarge(event, settings)) {
            var history = context.history().required(query(event, settings));
            match = history.count() >= settings.minSmallPayments();
            reason = match ? "SMALL_PAYMENT_BURST_DETECTED" : "SMALL_PAYMENT_COUNT_BELOW_LIMIT";
            facts.put("count", new RuleResult.Number(BigDecimal.valueOf(history.count())));
            facts.put("total", new RuleResult.Number(history.total()));
        }
        return new RuleResult(definition().id(), definition().algorithmVersion(),
                match ? RuleResult.Status.MATCH : RuleResult.Status.NO_MATCH, reason, facts);
    }
}
