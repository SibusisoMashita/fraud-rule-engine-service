package za.co.sibusiso.fraud.evaluation.rules;

import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.RuleId;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;
import za.co.sibusiso.fraud.policy.definition.RuleSettings;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    public PreparedPolicy prepare(PolicySnapshot policy) {
        var bindings = new TreeMap<RuleId, PreparedPolicy.Binding<?>>();
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
                            bindings.put(id, bind(implementation, configured.settings()));
                            ContractChecks.require(
                                    implementation.maximumEvidenceFacts() <= maximumEvidenceFacts,
                                    "Configured evidence limit cannot represent enabled rule: "
                                            + id.value());
                        });
        return new PreparedPolicy(policy, bindings);
    }

    /** Validates candidates without selecting or caching an active policy. */
    public void verify(PolicySnapshot policy) {
        prepare(policy);
    }

    private <C extends RuleSettings> PreparedPolicy.Binding<C> bind(
            FraudRule<C> rule, RuleSettings settings) {
        C typedSettings = rule.definition().settingsType().cast(settings);
        rule.validateConfiguration(typedSettings);
        return new PreparedPolicy.Binding<>(rule, typedSettings);
    }
}
