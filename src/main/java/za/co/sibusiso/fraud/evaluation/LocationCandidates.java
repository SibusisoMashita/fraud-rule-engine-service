package za.co.sibusiso.fraud.evaluation;

import java.time.Instant;
import java.util.List;
import za.co.sibusiso.fraud.domain.TransactionEvent.PhysicalLocation;

public record LocationCandidates(List<Observation> observations, boolean overflow) {
    public LocationCandidates { observations = List.copyOf(observations); }
    public record Observation(String eventId, Instant occurredAt, PhysicalLocation location) { }
}
