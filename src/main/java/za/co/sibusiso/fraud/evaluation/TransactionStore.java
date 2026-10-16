package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import za.co.sibusiso.fraud.domain.EvaluationOutcome;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.ingress.security.Caller;

public interface TransactionStore {
    record Stored(String requestHash, String json) { }
    record State(Instant lastAcceptedAt, long sequence) { }
    Optional<Stored> find(String scope, String eventId);
    void timeouts(int statementMillis, int lockMillis);
    void createCustomer(String scope, String customerId);
    State lockCustomer(String scope, String customerId);
    String bindInstrument(String scope, String instrumentId, String customerId);
    Instant databaseTime();
    SmallPaymentsHistory smallPayments(String scope, String customerId, SmallPaymentsQuery query, long highWatermark);
    LocationCandidates locations(String scope, String customerId, LocationQuery query, long highWatermark);
    BigDecimal aggregate(String scope, String customerId, HistoryQuery query, Instant acceptedAt, long highWatermark);
    void insertEvent(Caller caller, TransactionEvent event, String hash, Instant acceptedAt, long sequence);
    void insertEvaluation(String scope, String eventId, UUID id, String policyHash, String buildId,
            long highWatermark, Instant evaluatedAt, EvaluationOutcome outcome);
    void advance(String scope, String customerId, Instant acceptedAt, long sequence);
}
