package za.co.sibusiso.fraud.evaluation.history;

import za.co.sibusiso.fraud.domain.TransactionEvent.PhysicalLocation;

import java.time.Instant;
import java.util.List;

public record LocationCandidates(List<Observation> observations, boolean overflow) {
    public LocationCandidates {
        observations = List.copyOf(observations);
    }

    public record Observation(String eventId, Instant occurredAt, PhysicalLocation location) {}
}
