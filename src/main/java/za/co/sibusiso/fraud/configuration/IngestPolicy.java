package za.co.sibusiso.fraud.configuration;

import java.time.Instant;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EventTime;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.domain.TransactionEligibility;

public record IngestPolicy(int maxEventAgeSeconds, int maxFutureSkewSeconds) {
    public IngestPolicy {
        ContractChecks.require(maxEventAgeSeconds > 0 && maxFutureSkewSeconds >= 0, "Invalid ingestion time window");
    }

    /** Eligibility is intentionally separate from static identity validation and duplicate lookup. */
    public TransactionEligibility checkNewEvent(TransactionEvent event, CurrencyPolicy currencies, Instant referenceTime) {
        EventTime.validate(referenceTime);
        TransactionEligibility money = currencies.check(event.money());
        if (money != TransactionEligibility.ELIGIBLE) return money;
        if (event.occurredAt().isBefore(referenceTime.minusSeconds(maxEventAgeSeconds))) return TransactionEligibility.EVENT_TOO_OLD;
        if (event.occurredAt().isAfter(referenceTime.plusSeconds(maxFutureSkewSeconds))) return TransactionEligibility.EVENT_IN_FUTURE;
        return TransactionEligibility.ELIGIBLE;
    }
}
