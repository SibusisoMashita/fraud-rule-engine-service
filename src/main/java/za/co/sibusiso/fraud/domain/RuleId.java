package za.co.sibusiso.fraud.domain;

public record RuleId(String value) implements Comparable<RuleId> {
    public RuleId {
        ContractChecks.require(value != null && value.length() <= 64 && value.matches("[A-Z][A-Z0-9_]*"),
                "Invalid rule ID");
    }

    @Override
    public int compareTo(RuleId other) {
        return value.compareTo(other.value);
    }
}
