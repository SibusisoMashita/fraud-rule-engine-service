package za.co.sibusiso.fraud.evaluation.rules;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.history.HistoryFacts;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;
import za.co.sibusiso.fraud.policy.definition.RuleDefinition;
import za.co.sibusiso.fraud.policy.definition.RuleSettings;

public interface FraudRule<C extends RuleSettings> {
    record Context(Instant acceptedAt, long highWatermark, HistoryFacts history) {
        public Context(Instant acceptedAt, long highWatermark) { this(acceptedAt, highWatermark, new HistoryFacts(Map.of())); }
    }
    RuleDefinition<C> definition();
    int maximumEvidenceFacts();
    default void validateConfiguration(C settings) { }
    default Optional<SmallPaymentsQuery> smallPaymentsQuery(TransactionEvent event, C settings) { return Optional.empty(); }
    default Optional<LocationQuery> locationQuery(TransactionEvent event, C settings) { return Optional.empty(); }
    default Set<HistoryQuery> historyQueries(TransactionEvent event, C settings) { return Set.of(); }
    RuleResult evaluate(TransactionEvent event, C settings, Context context) throws Exception;
}
