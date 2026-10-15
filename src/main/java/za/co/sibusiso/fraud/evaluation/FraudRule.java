package za.co.sibusiso.fraud.evaluation;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import za.co.sibusiso.fraud.configuration.RuleDefinition;
import za.co.sibusiso.fraud.configuration.RuleSettings;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;

public interface FraudRule<C extends RuleSettings> {
    record Context(Instant acceptedAt, long highWatermark, HistoryFacts history) {
        public Context(Instant acceptedAt, long highWatermark) { this(acceptedAt, highWatermark, new HistoryFacts(Map.of())); }
    }
    RuleDefinition<C> definition();
    int maximumEvidenceFacts();
    default Set<HistoryQuery> historyQueries(TransactionEvent event, C settings) { return Set.of(); }
    RuleResult evaluate(TransactionEvent event, C settings, Context context) throws Exception;
}
