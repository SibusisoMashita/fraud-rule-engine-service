package za.co.sibusiso.fraud.policy.definition;

import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.TransactionEvent.Channel;
import za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource;

import java.util.Map;
import java.util.Set;

public final class BuiltInRuleSettings {
    private BuiltInRuleSettings() {}

    public record HighAmount(boolean enabled, Map<String, PolicyAmount> thresholds)
            implements RuleSettings {
        public HighAmount {
            thresholds = Map.copyOf(thresholds);
        }
    }

    public record Velocity(boolean enabled, int windowSeconds, int maxTransactions)
            implements RuleSettings {
        public Velocity {
            positive(windowSeconds, "Velocity window");
            positive(maxTransactions, "Velocity count");
        }
    }

    public record CumulativeSpend(
            boolean enabled, int windowSeconds, Map<String, PolicyAmount> thresholds)
            implements RuleSettings {
        public CumulativeSpend {
            positive(windowSeconds, "Spend window");
            thresholds = Map.copyOf(thresholds);
        }
    }

    public record ImpossibleTravel(
            boolean enabled,
            int maxComparisonAgeSeconds,
            int maxCandidates,
            double maxSpeedKmh,
            double minDistanceKm,
            double travelAllowanceKm,
            double maxAccuracyKm,
            java.util.List<LocationSource> trustedSources,
            java.util.List<Channel> eligibleChannels)
            implements RuleSettings {
        public ImpossibleTravel {
            positive(maxComparisonAgeSeconds, "Comparison window");
            positive(maxCandidates, "Candidate limit");
            ContractChecks.require(
                    maxCandidates < Integer.MAX_VALUE,
                    "Candidate limit leaves no overflow sentinel");
            ContractChecks.require(
                    Double.isFinite(maxSpeedKmh) && maxSpeedKmh > 0,
                    "Travel speed must be positive and finite");
            nonnegative(minDistanceKm, "Minimum distance");
            nonnegative(travelAllowanceKm, "Travel allowance");
            nonnegative(maxAccuracyKm, "Maximum accuracy");
            trustedSources = ordered(trustedSources);
            eligibleChannels = ordered(eligibleChannels);
            ContractChecks.require(
                    !trustedSources.isEmpty()
                            && !eligibleChannels.isEmpty()
                            && !eligibleChannels.contains(Channel.ECOMMERCE),
                    "Physical rule requires physical evidence sources/channels");
            Set<Channel> sourceChannels =
                    trustedSources.stream()
                            .map(
                                    source ->
                                            switch (source) {
                                                case VERIFIED_ATM_TERMINAL -> Channel.ATM;
                                                case VERIFIED_CARD_PRESENT_TERMINAL ->
                                                        Channel.CARD_PRESENT;
                                            })
                            .collect(java.util.stream.Collectors.toSet());
            ContractChecks.require(
                    sourceChannels.equals(Set.copyOf(eligibleChannels)),
                    "Travel sources and channels disagree");
        }
    }

    public record SmallPaymentBurst(
            boolean enabled,
            int windowSeconds,
            int minSmallPayments,
            Map<String, PolicyAmount> smallPaymentMaximum,
            Map<String, PolicyAmount> largePaymentThreshold)
            implements RuleSettings {
        public SmallPaymentBurst {
            positive(windowSeconds, "Sequence window");
            positive(minSmallPayments, "Small-payment count");
            smallPaymentMaximum = Map.copyOf(smallPaymentMaximum);
            largePaymentThreshold = Map.copyOf(largePaymentThreshold);
        }
    }

    private static <E extends Enum<E>> java.util.List<E> ordered(java.util.List<E> values) {
        ContractChecks.require(
                Set.copyOf(values).size() == values.size(), "Duplicate source/channel entry");
        return values.stream().sorted(java.util.Comparator.comparing(Enum::name)).toList();
    }

    private static void positive(int value, String name) {
        ContractChecks.require(value > 0, name + " must be positive");
    }

    private static void nonnegative(double value, String name) {
        ContractChecks.require(
                Double.isFinite(value) && value >= 0, name + " must be nonnegative and finite");
    }
}
