package za.co.sibusiso.fraud.evaluation.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EvaluationOutcome;
import za.co.sibusiso.fraud.domain.RuleId;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;
import za.co.sibusiso.fraud.policy.definition.RuleSettings;

public final class RuleEngine {
    private final Map<RuleId, FraudRule<?>> implementations;
    private final int maximumEvidenceFacts;

    public RuleEngine(List<FraudRule<?>> rules, int maximumEvidenceFacts) {
        this.maximumEvidenceFacts = maximumEvidenceFacts;
        var indexed = new TreeMap<RuleId, FraudRule<?>>();
        for (var rule : rules)
            ContractChecks.require(
                    indexed.putIfAbsent(rule.definition().id(), rule) == null,
                    "Duplicate rule implementation");
        implementations = Map.copyOf(indexed);
    }

    public void verify(PolicySnapshot policy) {
        policy.rules()
                .forEach(
                        (id, configured) -> {
                            if (!configured.settings().enabled()) return;
                            FraudRule<?> implementation = implementations.get(id);
                            ContractChecks.require(
                                    implementation != null
                                            && implementation
                                                    .definition()
                                                    .algorithmVersion()
                                                    .equals(
                                                            configured
                                                                    .definition()
                                                                    .algorithmVersion())
                                            && implementation
                                                    .definition()
                                                    .settingsType()
                                                    .equals(configured.definition().settingsType()),
                                    "Enabled policy rule is not supported by this build: "
                                            + id.value());
                            validate(implementation, configured.settings());
                            ContractChecks.require(
                                    implementation.maximumEvidenceFacts() <= maximumEvidenceFacts,
                                    "Configured evidence limit cannot represent enabled rule: "
                                            + id.value());
                        });
    }

    private <C extends RuleSettings> void validate(FraudRule<C> rule, RuleSettings settings) {
        rule.validateConfiguration(rule.definition().settingsType().cast(settings));
    }

    public Set<SmallPaymentsQuery> smallPaymentsQueries(
            TransactionEvent event, PolicySnapshot policy) {
        verify(policy);
        var queries = new LinkedHashSet<SmallPaymentsQuery>();
        for (var configured : policy.rules().values()) {
            if (configured.settings().enabled())
                smallPayments(
                                implementations.get(configured.definition().id()),
                                configured.settings(),
                                event)
                        .ifPresent(queries::add);
        }
        return Collections.unmodifiableSet(queries);
    }

    private <C extends RuleSettings> Optional<SmallPaymentsQuery> smallPayments(
            FraudRule<C> rule, RuleSettings settings, TransactionEvent event) {
        return rule.smallPaymentsQuery(event, rule.definition().settingsType().cast(settings));
    }

    public Set<LocationQuery> locationQueries(TransactionEvent event, PolicySnapshot policy) {
        verify(policy);
        var queries = new LinkedHashSet<LocationQuery>();
        for (var configured : policy.rules().values()) {
            if (configured.settings().enabled())
                location(
                                implementations.get(configured.definition().id()),
                                configured.settings(),
                                event)
                        .ifPresent(queries::add);
        }
        return Collections.unmodifiableSet(queries);
    }

    private <C extends RuleSettings> Optional<LocationQuery> location(
            FraudRule<C> rule, RuleSettings settings, TransactionEvent event) {
        return rule.locationQuery(event, rule.definition().settingsType().cast(settings));
    }

    public Set<HistoryQuery> historyQueries(TransactionEvent event, PolicySnapshot policy) {
        verify(policy);
        var queries = new LinkedHashSet<HistoryQuery>();
        for (var configured : policy.rules().values()) {
            if (configured.settings().enabled())
                queries.addAll(
                        queries(
                                implementations.get(configured.definition().id()),
                                configured.settings(),
                                event));
        }
        return Collections.unmodifiableSet(queries);
    }

    private <C extends RuleSettings> Set<HistoryQuery> queries(
            FraudRule<C> rule, RuleSettings settings, TransactionEvent event) {
        return rule.historyQueries(event, rule.definition().settingsType().cast(settings));
    }

    public EvaluationOutcome evaluate(
            TransactionEvent event,
            PolicySnapshot policy,
            FraudRule.Context context,
            ServiceLimits.Api limits)
            throws Exception {
        verify(policy);
        var results = new ArrayList<RuleResult>();
        for (var configured : policy.rules().values()) {
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
            } else
                results.add(
                        invoke(
                                implementations.get(definition.id()),
                                configured.settings(),
                                event,
                                context));
        }
        return EvaluationOutcome.aggregate(
                policy.expectedRules(),
                results,
                limits.maxRegisteredRules(),
                limits.maxEvidenceFactsPerRule());
    }

    private <C extends RuleSettings> RuleResult invoke(
            FraudRule<C> rule,
            RuleSettings settings,
            TransactionEvent event,
            FraudRule.Context context)
            throws Exception {
        return rule.evaluate(event, rule.definition().settingsType().cast(settings), context);
    }
}
