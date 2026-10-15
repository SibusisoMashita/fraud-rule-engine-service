package za.co.sibusiso.fraud.configuration;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.Resource;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.domain.RuleId;

public final class PolicyLoader {
    private final StrictYaml yaml;
    private final RuleRegistry registry;

    public PolicyLoader(StrictYaml yaml, RuleRegistry registry) {
        this.yaml = yaml;
        this.registry = registry;
    }

    public PolicySnapshot read(Resource resource) { return bind(yaml.read(resource, Document.class)); }
    public PolicySnapshot read(String text) { return bind(yaml.read(text, Document.class)); }

    private PolicySnapshot bind(Document input) {
        ContractChecks.require(input.schemaVersion() == 1 && input.canonicalizationVersion() == 1,
                "Unsupported policy or canonicalization version");
        ContractChecks.require(input.rules().keySet().equals(registry.definitions().keySet()),
                "Policy must configure exactly the registered rule keys; disable rules explicitly");
        var currencies = new CurrencyPolicy(input.supportedCurrencies());
        var rules = new TreeMap<RuleId, PolicySnapshot.ConfiguredRule<?>>();
        registry.definitions().forEach((key, definition) -> rules.put(definition.id(),
                bindRule(definition, input.rules().get(key), currencies)));
        ContractChecks.require(rules.values().stream().anyMatch(rule -> rule.settings().enabled()), "All-disabled policy is invalid");
        return new PolicySnapshot(currencies, input.ingest(), rules);
    }

    private <C extends RuleSettings> PolicySnapshot.ConfiguredRule<C> bindRule(
            RuleDefinition<C> definition, Object input, CurrencyPolicy currencies) {
        C settings = yaml.convert(input, definition.settingsType());
        java.util.Objects.requireNonNull(settings, "Rule settings are required");
        definition.validator().accept(settings, currencies);
        return new PolicySnapshot.ConfiguredRule<>(definition, settings);
    }

    public record Document(int schemaVersion, int canonicalizationVersion,
            Map<String, CurrencyPolicy.Bounds> supportedCurrencies, IngestPolicy ingest, Map<String, Object> rules) { }
}
