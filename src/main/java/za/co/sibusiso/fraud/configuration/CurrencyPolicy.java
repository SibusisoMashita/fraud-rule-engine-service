package za.co.sibusiso.fraud.configuration;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.Money;
import za.co.sibusiso.fraud.domain.TransactionEligibility;

public record CurrencyPolicy(Map<String, Bounds> currencies) {
    public CurrencyPolicy {
        currencies = Collections.unmodifiableMap(new TreeMap<>(currencies));
        ContractChecks.require(!currencies.isEmpty(), "At least one currency is required");
        currencies.keySet().forEach(ContractChecks::currency);
        currencies.values().forEach(value -> java.util.Objects.requireNonNull(value, "Currency bounds are required"));
    }

    public record Bounds(int scale, PolicyAmount maxAmount) {
        public Bounds {
            ContractChecks.require(scale >= 0 && scale <= 4, "Currency scale exceeds storage precision");
            java.util.Objects.requireNonNull(maxAmount, "Currency maximum is required");
            ContractChecks.require(maxAmount.value().scale() <= scale, "Currency maximum has excess precision");
        }
    }

    public void validateThresholds(Map<String, PolicyAmount> thresholds) {
        ContractChecks.require(thresholds.keySet().equals(currencies.keySet()), "Currency thresholds must cover exactly the supported currencies");
        thresholds.forEach((currency, amount) -> {
            Bounds bound = currencies.get(currency);
            ContractChecks.require(amount.value().scale() <= bound.scale(), "Threshold has excess currency precision");
            ContractChecks.require(amount.value().compareTo(bound.maxAmount().value()) <= 0, "Threshold exceeds currency maximum");
        });
    }

    public TransactionEligibility check(Money money) {
        Bounds bound = currencies.get(money.currency());
        if (bound == null) return TransactionEligibility.UNSUPPORTED_CURRENCY;
        if (money.amount().scale() > bound.scale()) return TransactionEligibility.AMOUNT_SCALE;
        if (money.amount().compareTo(bound.maxAmount().value()) > 0) return TransactionEligibility.AMOUNT_LIMIT;
        return TransactionEligibility.ELIGIBLE;
    }

}
