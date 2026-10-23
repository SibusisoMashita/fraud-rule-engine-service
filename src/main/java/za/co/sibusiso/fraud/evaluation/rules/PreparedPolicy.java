package za.co.sibusiso.fraud.evaluation.rules;

import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.domain.EvaluationOutcome;
import za.co.sibusiso.fraud.domain.RuleId;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;
import za.co.sibusiso.fraud.policy.definition.RuleSettings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** A policy snapshot bound once to its typed rule implementations. */
public final class PreparedPolicy {
    record Binding<C extends RuleSettings>(FraudRule<C> rule, C settings) {
        Set<HistoryQuery> historyQueries(TransactionEvent event) {
            return rule.historyQueries(event, settings);
        }

        Optional<LocationQuery> locationQuery(TransactionEvent event) {
            return rule.locationQuery(event, settings);
        }

        Optional<SmallPaymentsQuery> smallPaymentsQuery(TransactionEvent event) {
            return rule.smallPaymentsQuery(event, settings);
        }

        RuleResult evaluate(TransactionEvent event, FraudRule.Context context)
                throws RuleEvaluationException {
            return rule.evaluate(event, settings, context);
        }
    }

    private final PolicySnapshot snapshot;
    private final Map<RuleId, Binding<?>> bindings;

    PreparedPolicy(PolicySnapshot snapshot, Map<RuleId, Binding<?>> bindings) {
        this.snapshot = snapshot;
        this.bindings = Collections.unmodifiableMap(new TreeMap<>(bindings));
    }

    public PolicySnapshot snapshot() {
        return snapshot;
    }

    public Set<HistoryQuery> historyQueries(TransactionEvent event) {
        var queries = new LinkedHashSet<HistoryQuery>();
        for (var binding : bindings.values()) queries.addAll(binding.historyQueries(event));
        return Collections.unmodifiableSet(queries);
    }

    public Set<LocationQuery> locationQueries(TransactionEvent event) {
        var queries = new LinkedHashSet<LocationQuery>();
        for (var binding : bindings.values()) binding.locationQuery(event).ifPresent(queries::add);
        return Collections.unmodifiableSet(queries);
    }

    public Set<SmallPaymentsQuery> smallPaymentsQueries(TransactionEvent event) {
        var queries = new LinkedHashSet<SmallPaymentsQuery>();
        for (var binding : bindings.values())
            binding.smallPaymentsQuery(event).ifPresent(queries::add);
        return Collections.unmodifiableSet(queries);
    }

    public EvaluationOutcome evaluate(
            TransactionEvent event, FraudRule.Context context, ServiceLimits.Api limits)
            throws RuleEvaluationException {
        var results = new ArrayList<RuleResult>();
        for (var configured : snapshot.rules().values()) {
            var definition = configured.definition();
            if (!configured.settings().enabled()) {
                results.add(
                        new RuleResult(
                                definition.id(),
                                definition.algorithmVersion(),
                                RuleResult.Status.DISABLED,
                                "RULE_DISABLED",
                                Map.of(
                                        RuleResult.EVIDENCE_SCHEMA_VERSION_KEY,
                                        new RuleResult.Number(BigDecimal.ONE))));
            } else {
                results.add(bindings.get(definition.id()).evaluate(event, context));
            }
        }
        return EvaluationOutcome.aggregate(
                snapshot.expectedRules(),
                results,
                limits.maxRegisteredRules(),
                limits.maxEvidenceFactsPerRule());
    }
}
