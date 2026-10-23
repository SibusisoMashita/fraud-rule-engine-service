package za.co.sibusiso.fraud.evaluation.application;

import java.time.Instant;
import java.util.List;

public interface EvaluationQueryStore {
    record Row(EvaluationQuery.Position position, String json) {}

    Instant now();

    void timeouts(int statementMillis, int lockMillis);

    List<Row> page(String scope, EvaluationQuery.Window window);
}
