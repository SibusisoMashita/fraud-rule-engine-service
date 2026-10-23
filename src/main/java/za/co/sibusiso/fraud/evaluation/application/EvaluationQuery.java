package za.co.sibusiso.fraud.evaluation.application;

import za.co.sibusiso.fraud.domain.EvaluationOutcome.Decision;

import java.time.Instant;
import java.util.UUID;

public final class EvaluationQuery {
    private EvaluationQuery() {}

    public record Request(
            String customerId,
            Decision decision,
            Instant from,
            Instant to,
            Integer pageSize,
            String cursor) {}

    public record Position(Instant acceptedAt, UUID id) {}

    public record Window(
            String customerId,
            Decision decision,
            Instant from,
            Instant to,
            int pageSize,
            Position after) {}
}
