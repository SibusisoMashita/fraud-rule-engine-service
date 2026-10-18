package za.co.sibusiso.fraud.policy.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import za.co.sibusiso.fraud.domain.ContractChecks;

public record PolicyAmount(String decimal) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public PolicyAmount {
        ContractChecks.amount(decimal);
    }

    public BigDecimal value() { return new BigDecimal(decimal); }

    @JsonValue
    public String canonicalValue() { return value().stripTrailingZeros().toPlainString(); }
}
