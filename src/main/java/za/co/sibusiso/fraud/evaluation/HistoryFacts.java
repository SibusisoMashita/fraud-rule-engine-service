package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.util.Map;

/** Immutable facts from the same processing transaction; missing facts never mean zero. */
public record HistoryFacts(Map<HistoryQuery, BigDecimal> aggregates, Map<LocationQuery, LocationCandidates> locations) {
    public HistoryFacts { aggregates = Map.copyOf(aggregates); locations = Map.copyOf(locations); }
    public HistoryFacts(Map<HistoryQuery, BigDecimal> aggregates) { this(aggregates, Map.of()); }
    public LocationCandidates required(LocationQuery query) {
        LocationCandidates facts = locations.get(query);
        if (facts == null) throw new IllegalStateException("Required location history was not loaded");
        return facts;
    }
    public BigDecimal required(HistoryQuery query) {
        BigDecimal fact = aggregates.get(query);
        if (fact == null) throw new IllegalStateException("Required history aggregate was not loaded");
        if (fact.signum() < 0) throw new IllegalStateException("History aggregate cannot be negative");
        return fact;
    }
}
