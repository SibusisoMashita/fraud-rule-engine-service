package za.co.sibusiso.fraud.evaluation.application;

import java.time.Instant;
import java.util.UUID;
import za.co.sibusiso.fraud.domain.EvaluationOutcome.Decision;

public final class EvaluationQuery {
    private EvaluationQuery() { }
    public record Request(String customerId, Decision decision, Instant from, Instant to, Integer pageSize, String cursor) { }
    public record Position(Instant acceptedAt, UUID id) { }
    public record Window(String customerId, Decision decision, Instant from, Instant to, int pageSize, Position after) { }
}
