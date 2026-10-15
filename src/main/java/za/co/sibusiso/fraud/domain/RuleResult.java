package za.co.sibusiso.fraud.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public record RuleResult(RuleId ruleId, String algorithmVersion, Status status,
        String reasonCode, Map<String, Fact> evidence) {
    public enum Status { MATCH, NO_MATCH, NOT_APPLICABLE, INSUFFICIENT_DATA, DISABLED }

    public sealed interface Fact permits Text, Number, Flag { }
    public record Text(String value) implements Fact {
        public Text { ContractChecks.scalarText(value, 256); }
    }
    public record Number(BigDecimal value) implements Fact {
        public Number { ContractChecks.decimal(value); }
    }
    public record Flag(boolean value) implements Fact { }

    public RuleResult {
        Objects.requireNonNull(ruleId, "Rule ID is required");
        algorithmVersion = ContractChecks.text(algorithmVersion, 32);
        Objects.requireNonNull(status, "Status is required");
        reasonCode = ContractChecks.text(reasonCode, 64);
        evidence = Map.copyOf(evidence);
        ContractChecks.require(evidence.size() <= 16, "Too many evidence facts");
        evidence.keySet().forEach(key -> ContractChecks.require(key.matches("[a-zA-Z][a-zA-Z0-9]{0,63}"),
                "Invalid evidence key"));
        ContractChecks.require(evidence.get("evidenceSchemaVersion") instanceof Number version
                && version.value().signum() > 0 && version.value().stripTrailingZeros().scale() <= 0,
                "Evidence requires a positive integer schema version");
    }
}
