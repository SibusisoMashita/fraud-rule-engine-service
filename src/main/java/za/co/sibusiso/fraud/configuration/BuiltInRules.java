package za.co.sibusiso.fraud.configuration;

import java.util.List;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.RuleId;
import za.co.sibusiso.fraud.configuration.BuiltInRuleSettings.*;

/** Stable configuration and algorithm identities, independent of execution implementations. */
public final class BuiltInRules {
    private BuiltInRules() { }

    public static List<RuleDefinition<?>> definitions() {
        return List.of(highAmount(), velocity(), cumulativeSpend(), impossibleTravel(), smallPaymentBurst());
    }

    public static RuleDefinition<HighAmount> highAmount() {
        return new RuleDefinition<>("highAmount", new RuleId("HIGH_AMOUNT"), "1", HighAmount.class,
                (settings, currencies) -> currencies.validateThresholds(settings.thresholds()));
    }

    public static RuleDefinition<Velocity> velocity() {
        return new RuleDefinition<>("transactionVelocity", new RuleId("TRANSACTION_VELOCITY"), "1", Velocity.class,
                (settings, currencies) -> { });
    }

    public static RuleDefinition<CumulativeSpend> cumulativeSpend() {
        return new RuleDefinition<>("cumulativeSpend", new RuleId("CUMULATIVE_SPEND"), "1", CumulativeSpend.class,
                (settings, currencies) -> currencies.validateThresholds(settings.thresholds()));
    }

    public static RuleDefinition<ImpossibleTravel> impossibleTravel() {
        return new RuleDefinition<>("impossibleTravel", new RuleId("IMPOSSIBLE_TRAVEL"), "1", ImpossibleTravel.class,
                (settings, currencies) -> { });
    }

    public static RuleDefinition<SmallPaymentBurst> smallPaymentBurst() {
        return new RuleDefinition<>("smallPaymentBurstThenLarge", new RuleId("SMALL_PAYMENT_BURST_THEN_LARGE"),
                "1", SmallPaymentBurst.class, (settings, currencies) -> {
                    currencies.validateThresholds(settings.smallPaymentMaximum());
                    currencies.validateThresholds(settings.largePaymentThreshold());
                    currencies.currencies().keySet().forEach(currency -> ContractChecks.require(
                            settings.smallPaymentMaximum().get(currency).value().compareTo(
                                    settings.largePaymentThreshold().get(currency).value()) < 0,
                            "Small-payment maximum must be below the large-payment threshold"));
                });
    }
}
