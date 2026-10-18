package za.co.sibusiso.fraud.policy.definition;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.EvaluationOutcome;
import za.co.sibusiso.fraud.domain.RuleId;

public record RuleRegistry(Map<String, RuleDefinition<?>> definitions) {
    public RuleRegistry {
        definitions = Collections.unmodifiableMap(new TreeMap<>(definitions));
        ContractChecks.require(
                !definitions.isEmpty() && definitions.size() <= EvaluationOutcome.MAX_RULES,
                "Invalid rule registry size");
        var ids = new HashSet<RuleId>();
        definitions.forEach(
                (key, rule) -> {
                    ContractChecks.require(
                            key.equals(rule.configurationKey()), "Registry key mismatch");
                    ContractChecks.require(ids.add(rule.id()), "Duplicate rule ID");
                });
    }

    public static RuleRegistry of(Collection<RuleDefinition<?>> definitions, int maximum) {
        ContractChecks.require(
                maximum > 0
                        && maximum <= EvaluationOutcome.MAX_RULES
                        && definitions.size() <= maximum,
                "Rule registry exceeds its configured limit");
        var byKey = new TreeMap<String, RuleDefinition<?>>();
        for (RuleDefinition<?> definition : definitions) {
            ContractChecks.require(
                    byKey.putIfAbsent(definition.configurationKey(), definition) == null,
                    "Duplicate rule configuration key");
        }
        return new RuleRegistry(byKey);
    }
}
