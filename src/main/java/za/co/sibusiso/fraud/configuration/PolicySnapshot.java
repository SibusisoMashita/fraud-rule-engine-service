package za.co.sibusiso.fraud.configuration;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import za.co.sibusiso.fraud.contracts.CanonicalJson;
import za.co.sibusiso.fraud.domain.EvaluationOutcome.ExpectedRule;
import za.co.sibusiso.fraud.domain.RuleId;

public final class PolicySnapshot {
    public record ConfiguredRule<C extends RuleSettings>(RuleDefinition<C> definition, C settings) { }

    private final CurrencyPolicy currencies;
    private final IngestPolicy ingest;
    private final Map<RuleId, ConfiguredRule<?>> rules;
    private final String canonicalForm;
    private final String policyHash;

    PolicySnapshot(CurrencyPolicy currencies, IngestPolicy ingest, Map<RuleId, ConfiguredRule<?>> rules) {
        this.currencies = currencies;
        this.ingest = ingest;
        this.rules = Collections.unmodifiableMap(new TreeMap<>(rules));
        var effectiveRules = new TreeMap<String, Object>();
        rules.forEach((id, rule) -> effectiveRules.put(id.value(), Map.of(
                "algorithmVersion", rule.definition().algorithmVersion(), "settings", rule.settings())));
        Map<String, Object> document = Map.of("schemaVersion", 1, "canonicalizationVersion", 1,
                "supportedCurrencies", currencies.currencies(), "ingest", ingest, "rules", effectiveRules);
        this.canonicalForm = CanonicalJson.encode(document);
        this.policyHash = CanonicalJson.sha256("fraud-policy-v1", document);
    }

    public CurrencyPolicy currencies() { return currencies; }
    public IngestPolicy ingest() { return ingest; }
    public Map<RuleId, ConfiguredRule<?>> rules() { return rules; }
    public String canonicalForm() { return canonicalForm; }
    public String policyHash() { return policyHash; }

    public Map<RuleId, ExpectedRule> expectedRules() {
        var expected = new TreeMap<RuleId, ExpectedRule>();
        rules.forEach((id, rule) -> expected.put(id,
                new ExpectedRule(rule.definition().algorithmVersion(), rule.settings().enabled())));
        return Map.copyOf(expected);
    }
}
