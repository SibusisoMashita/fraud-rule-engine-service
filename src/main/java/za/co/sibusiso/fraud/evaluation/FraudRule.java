package za.co.sibusiso.fraud.evaluation;

import java.time.Instant;
import za.co.sibusiso.fraud.configuration.RuleDefinition;
import za.co.sibusiso.fraud.configuration.RuleSettings;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;

public interface FraudRule<C extends RuleSettings> {
    record Context(Instant acceptedAt, long highWatermark) { }
    RuleDefinition<C> definition();
    int maximumEvidenceFacts();
    RuleResult evaluate(TransactionEvent event, C settings, Context context) throws Exception;
}
