package za.co.sibusiso.fraud.evaluation.history;

import za.co.sibusiso.fraud.domain.ContractChecks;

import java.math.BigDecimal;

/** Count and decimal sum from one database aggregate, without loading individual payments. */
public record SmallPaymentsHistory(long count, BigDecimal total) {
    public SmallPaymentsHistory {
        ContractChecks.require(
                count >= 0 && total != null && total.signum() >= 0, "Invalid small-payment facts");
    }
}
