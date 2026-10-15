package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.*;

public final class RuleEngine {
    private final Map<RuleId, FraudRule<?>> implementations;
    private final int maximumEvidenceFacts;
    public RuleEngine(List<FraudRule<?>> rules, int maximumEvidenceFacts) {
        this.maximumEvidenceFacts = maximumEvidenceFacts;
        var indexed = new TreeMap<RuleId, FraudRule<?>>();
        for (var rule : rules) ContractChecks.require(indexed.putIfAbsent(rule.definition().id(), rule) == null,
                "Duplicate rule implementation");
        implementations = Map.copyOf(indexed);
    }

    public void verify(PolicySnapshot policy) {
        policy.rules().forEach((id, configured) -> {
            if (!configured.settings().enabled()) return;
            FraudRule<?> implementation = implementations.get(id);
            ContractChecks.require(implementation != null
                    && implementation.definition().algorithmVersion().equals(configured.definition().algorithmVersion())
                    && implementation.definition().settingsType().equals(configured.definition().settingsType()),
                    "Enabled policy rule is not supported by this build: " + id.value());
            ContractChecks.require(implementation.maximumEvidenceFacts() <= maximumEvidenceFacts,
                    "Configured evidence limit cannot represent enabled rule: " + id.value());
        });
    }

    public EvaluationOutcome evaluate(TransactionEvent event, PolicySnapshot policy, FraudRule.Context context,
            ServiceLimits.Api limits) throws Exception {
        verify(policy);
        var results = new ArrayList<RuleResult>();
        for (var configured : policy.rules().values()) {
            var definition = configured.definition();
            if (!configured.settings().enabled()) {
                results.add(new RuleResult(definition.id(), definition.algorithmVersion(), RuleResult.Status.DISABLED,
                        "RULE_DISABLED", Map.of("evidenceSchemaVersion", new RuleResult.Number(BigDecimal.ONE))));
            } else results.add(invoke(implementations.get(definition.id()), configured.settings(), event, context));
        }
        return EvaluationOutcome.aggregate(policy.expectedRules(), results, limits.maxRegisteredRules(), limits.maxEvidenceFactsPerRule());
    }

    private <C extends RuleSettings> RuleResult invoke(FraudRule<C> rule, RuleSettings settings,
            TransactionEvent event, FraudRule.Context context) throws Exception {
        return rule.evaluate(event, rule.definition().settingsType().cast(settings), context);
    }
}
