package za.co.sibusiso.fraud.evaluation.history;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.TransactionEvent.Channel;
import za.co.sibusiso.fraud.domain.TransactionEvent.LocationSource;

/** Event-time physical neighbors; separate from receipt-time numeric aggregates. */
public record LocationQuery(String instrumentId, Instant occurredAt, int comparisonAgeSeconds, int maxCandidates,
        BigDecimal maxAccuracyKm, List<LocationSource> sources, List<Channel> channels) {
    public LocationQuery {
        instrumentId = ContractChecks.identifier(instrumentId);
        java.util.Objects.requireNonNull(occurredAt);
        ContractChecks.require(comparisonAgeSeconds > 0 && maxCandidates > 0 && maxCandidates < Integer.MAX_VALUE,
                "Invalid location history bounds");
        ContractChecks.require(maxAccuracyKm.signum() >= 0, "Invalid location uncertainty");
        sources = List.copyOf(sources); channels = List.copyOf(channels);
    }
}
