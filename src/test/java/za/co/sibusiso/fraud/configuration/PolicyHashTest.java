package za.co.sibusiso.fraud.configuration;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PolicyHashTest {
    private static final String POLICY = ConfigurationTest.text("config/policy.example.yaml");

    @Test
    void matchesAnIndependentCanonicalPolicyVector() {
        String input = """
                schemaVersion: 1
                canonicalizationVersion: 1
                supportedCurrencies:
                  ZAR: {scale: 2, maxAmount: "999.99"}
                ingest: {maxEventAgeSeconds: 3600, maxFutureSkewSeconds: 30}
                rules:
                  highAmount: {enabled: true, thresholds: {ZAR: "25.00"}}
                """;
        var policy = new PolicyLoader(new StrictYaml(), RuleRegistry.of(List.of(BuiltInRules.highAmount()), 64)).read(input);
        String expected = """
                {"canonicalizationVersion":1,"ingest":{"maxEventAgeSeconds":3600,"maxFutureSkewSeconds":30},"rules":{"HIGH_AMOUNT":{"algorithmVersion":"1","settings":{"enabled":true,"thresholds":{"ZAR":"25"}}}},"schemaVersion":1,"supportedCurrencies":{"ZAR":{"maxAmount":"999.99","scale":2}}}
                """.strip();
        assertThat(policy.canonicalForm()).isEqualTo(expected);
        // Independently calculated SHA-256 of UTF-8 "fraud-policy-v1\n" + the literal above.
        assertThat(policy.policyHash()).isEqualTo("f8ec78ccfb9dac5d45d36a452ed458fbafa451235b8d98b408b93ccec08738c9");
    }

    @Test
    void formattingDecimalSpellingAndSetOrderDoNotChangeEffectivePolicyIdentity() {
        var loader = loader();
        String modified = POLICY.replace("schemaVersion: 1\ncanonicalizationVersion: 1", "canonicalizationVersion: 1\nschemaVersion: 1")
                .replace("25000.00", "25000")
                .replace("[ATM, CARD_PRESENT]", "[CARD_PRESENT, ATM]")
                .replace("[VERIFIED_ATM_TERMINAL, VERIFIED_CARD_PRESENT_TERMINAL]",
                        "[VERIFIED_CARD_PRESENT_TERMINAL, VERIFIED_ATM_TERMINAL]");
        assertThat(loader.read("# reordered settings\n" + modified).policyHash()).isEqualTo(loader.read(POLICY).policyHash());
    }

    @Test
    void policyThresholdsEnabledStateAndAlgorithmVersionChangeIdentity() {
        var loader = loader();
        String original = loader.read(POLICY).policyHash();
        assertThat(loader.read(POLICY.replace("25000.00", "25000.01")).policyHash()).isNotEqualTo(original);
        assertThat(loader.read(POLICY.replaceFirst("enabled: false", "enabled: true")).policyHash()).isNotEqualTo(original);
        var definitions = new ArrayList<>(BuiltInRules.definitions());
        var high = BuiltInRules.highAmount();
        definitions.set(0, new RuleDefinition<>(high.configurationKey(), high.id(), "2", high.settingsType(), high.validator()));
        assertThat(new PolicyLoader(new StrictYaml(), RuleRegistry.of(definitions, 64)).read(POLICY).policyHash()).isNotEqualTo(original);
    }

    @Test
    void operationalTuningIsAbsentFromThePolicyDocument() {
        var yaml = new StrictYaml();
        var before = yaml.read(ConfigurationTest.text("config/service.example.yaml"), ServiceLimits.class);
        var after = yaml.read(ConfigurationTest.text("config/service.example.yaml")
                .replace("capacity: 30", "capacity: 31"), ServiceLimits.class);
        assertThat(before).isNotEqualTo(after);
        var policy = loader().read(POLICY);
        assertThat(policy.canonicalForm()).doesNotContain("capacity", "redisTimeoutMillis", "operations", "api");
        assertThat(loader().read(POLICY).policyHash()).isEqualTo(policy.policyHash());
    }

    private PolicyLoader loader() { return new PolicyLoader(new StrictYaml(), RuleRegistry.of(BuiltInRules.definitions(), 64)); }
}
