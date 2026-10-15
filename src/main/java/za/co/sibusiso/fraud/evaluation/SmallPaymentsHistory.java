package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import za.co.sibusiso.fraud.domain.ContractChecks;

/** Count and decimal sum from one database aggregate, without loading individual payments. */
public record SmallPaymentsHistory(long count, BigDecimal total) {
    public SmallPaymentsHistory {
        ContractChecks.require(count >= 0 && total != null && total.signum() >= 0, "Invalid small-payment facts");
    }
}
