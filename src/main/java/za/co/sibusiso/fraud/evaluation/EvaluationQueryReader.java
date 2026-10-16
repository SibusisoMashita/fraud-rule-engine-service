package za.co.sibusiso.fraud.evaluation;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.CanonicalJson;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.domain.EventTime;
import za.co.sibusiso.fraud.policy.ActivePolicyProvider;

@Service
@Profile("!migrate & !policy-admin")
public class EvaluationQueryReader {
    private final EvaluationQueryStore store;
    private final EvaluationCursor cursors;
    private final ActivePolicyProvider policies;
    private final ServiceLimits limits;
    private final JsonMapper json = JsonMapper.builder().build();

    public EvaluationQueryReader(
            EvaluationQueryStore store,
            EvaluationCursor cursors,
            ActivePolicyProvider policies,
            ServiceLimits limits) {
        this.store = store;
        this.cursors = cursors;
        this.policies = policies;
        this.limits = limits;
    }

    @Transactional(readOnly = true, timeoutString = "${fraud.transaction-timeout-seconds}")
    public String list(String scope, EvaluationQuery.Request request, LongSupplier remaining) {
        var budget = new WorkBudget(remaining, limits.operations());
        int pageSize =
                request.pageSize() == null ? limits.api().defaultPageSize() : request.pageSize();
        if (pageSize < 1 || pageSize > limits.api().maxPageSize())
            throw new FlowFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.QUERY_LIMIT_EXCEEDED);
        String binding = cursors.binding(scope, request, pageSize);
        Instant from;
        Instant to;
        EvaluationQuery.Position after = null;
        if (request.cursor() == null) {
            prepare(budget);
            to = request.to() == null ? EventTime.validate(store.now()) : request.to();
            from =
                    request.from() == null
                            ? to.minus(limits.api().defaultQueryRangeDays(), ChronoUnit.DAYS)
                            : request.from();
        } else {
            var state = cursors.decode(request.cursor(), binding);
            from = EventTime.parse(state.from());
            to = EventTime.parse(state.to());
            after =
                    new EvaluationQuery.Position(
                            EventTime.parse(state.after()), UUID.fromString(state.id()));
        }
        try {
            EventTime.validate(from);
            EventTime.validate(to);
        } catch (RuntimeException invalid) {
            throw new FlowFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.INVALID_QUERY_RANGE);
        }
        Duration range = Duration.between(from, to);
        if (range.isNegative()
                || range.isZero()
                || range.compareTo(Duration.ofDays(limits.api().maxQueryRangeDays())) > 0)
            throw new FlowFailure(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.INVALID_QUERY_RANGE);
        var window =
                new EvaluationQuery.Window(
                        request.customerId(), request.decision(), from, to, pageSize, after);
        prepare(budget);
        var rows = store.page(scope, window);
        budget.remaining();
        boolean hasMore = rows.size() > pageSize;
        var page = hasMore ? rows.subList(0, pageSize) : rows;
        var response = new LinkedHashMap<String, Object>();
        response.put("items", page.stream().map(row -> json.readTree(row.json())).toList());
        if (hasMore)
            response.put("nextCursor", cursors.next(binding, window, page.getLast().position()));
        String result = CanonicalJson.encode(response);
        budget.remaining();
        return result;
    }

    @Transactional(readOnly = true, timeoutString = "${fraud.transaction-timeout-seconds}")
    public String rules(LongSupplier remaining) {
        var budget = new WorkBudget(remaining, limits.operations());
        prepare(budget);
        var snapshot = policies.current();
        budget.remaining();
        var rules =
                snapshot.rules().entrySet().stream()
                        .map(
                                entry ->
                                        Map.of(
                                                "ruleId",
                                                entry.getKey().value(),
                                                "algorithmVersion",
                                                entry.getValue().definition().algorithmVersion(),
                                                "enabled",
                                                entry.getValue().settings().enabled(),
                                                "settings",
                                                entry.getValue().settings()))
                        .toList();
        String result =
                CanonicalJson.encode(Map.of("policyHash", snapshot.policyHash(), "rules", rules));
        budget.remaining();
        return result;
    }

    private void prepare(WorkBudget budget) {
        store.timeouts(budget.statementMillis(), budget.lockMillis());
    }
}
