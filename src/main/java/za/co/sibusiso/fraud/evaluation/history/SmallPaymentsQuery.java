package za.co.sibusiso.fraud.evaluation.history;

import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EventTime;

import java.math.BigDecimal;
import java.time.Instant;

/** Earlier same-currency small payments in an open event-time window. */
public record SmallPaymentsQuery(
        Instant occurredAt, int windowSeconds, String currency, BigDecimal maximumAmount) {
    public SmallPaymentsQuery {
        occurredAt = EventTime.validate(occurredAt);
        ContractChecks.require(windowSeconds > 0, "Small-payment window must be positive");
        currency = ContractChecks.currency(currency);
        maximumAmount = ContractChecks.amount(maximumAmount);
    }
}
