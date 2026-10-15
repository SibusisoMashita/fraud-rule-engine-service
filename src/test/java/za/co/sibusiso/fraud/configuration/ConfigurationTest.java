package za.co.sibusiso.fraud.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.ClassPathResource;
import za.co.sibusiso.fraud.domain.RuleId;

import static org.assertj.core.api.Assertions.*;

class ConfigurationTest {
    private static final String POLICY = text("config/policy.example.yaml");
    private static final String LIMITS = text("config/service.example.yaml");
    private final StrictYaml yaml = new StrictYaml();
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void shippedConfigurationBindsToImmutableTypedValues() {
        var policy = loader().read(POLICY);
        var limits = yaml.read(LIMITS, ServiceLimits.class);
        assertThat(policy.rules()).hasSize(5);
        assertThat(policy.rules().get(new RuleId("HIGH_AMOUNT")).settings()).isInstanceOf(BuiltInRuleSettings.HighAmount.class);
        assertThat(policy.currencies().currencies().get("ZAR").scale()).isEqualTo(2);
        assertThat(limits.api().maxRegisteredRules()).isEqualTo(64);
        assertThatThrownBy(() -> policy.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        var settings = (BuiltInRuleSettings.HighAmount) policy.rules().get(new RuleId("HIGH_AMOUNT")).settings();
        assertThatThrownBy(() -> settings.thresholds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPolicies")
    void rejectsInvalidPolicy(String description, String input) {
        assertThatThrownBy(() -> loader().read(input)).isInstanceOf(RuntimeException.class);
    }

    static Stream<Arguments> invalidPolicies() {
        return Stream.of(
                change(POLICY, "schema", "schemaVersion: 1", "schemaVersion: 2"),
                change(POLICY, "canonical version", "canonicalizationVersion: 1", "canonicalizationVersion: 2"),
                Arguments.of("unknown root", POLICY + "unexpected: true\n"),
                change(POLICY, "unknown rule", "  highAmount:", "  unknownRule:"),
                change(POLICY, "unknown nested field", "  highAmount:", "  highAmount:\n    typo: 1"),
                change(POLICY, "missing enabled", "    enabled: true", ""),
                change(POLICY, "missing schema", "schemaVersion: 1", ""),
                change(POLICY, "duplicate field", "schemaVersion: 1", "schemaVersion: 1\nschemaVersion: 1"),
                change(POLICY, "duplicate rule", "  highAmount:", "  highAmount: {enabled: false, thresholds: {ZAR: \"1.00\"}}\n  highAmount:"),
                Arguments.of("all disabled", POLICY.replace("enabled: true", "enabled: false")),
                change(POLICY, "quoted boolean", "enabled: true", "enabled: \"true\""),
                change(POLICY, "YAML 1.1 boolean", "enabled: true", "enabled: yes"),
                change(POLICY, "null boolean", "enabled: true", "enabled: null"),
                change(POLICY, "invalid currency", "  ZAR: {scale", "  zar: {scale"),
                change(POLICY, "scale overflow", "scale: 2", "scale: 5"),
                change(POLICY, "missing currency threshold", "thresholds: {ZAR: \"25000.00\"}", "thresholds: {}"),
                change(POLICY, "extra currency threshold", "thresholds: {ZAR: \"25000.00\"}", "thresholds: {ZAR: \"25000.00\", USD: \"1.00\"}"),
                change(POLICY, "threshold precision", "25000.00", "25000.001"),
                change(POLICY, "threshold maximum", "25000.00", "9999999999999.00"),
                change(POLICY, "scientific money", "25000.00", "2.5e4"),
                change(POLICY, "numeric money", "\"25000.00\"", "25000.00"),
                change(POLICY, "zero window", "windowSeconds: 600", "windowSeconds: 0"),
                change(POLICY, "fractional count", "maxTransactions: 5", "maxTransactions: 5.5"),
                change(POLICY, "integer overflow", "maxTransactions: 5", "maxTransactions: 2147483648"),
                change(POLICY, "zero count", "maxTransactions: 5", "maxTransactions: 0"),
                change(POLICY, "negative skew", "maxFutureSkewSeconds: 120", "maxFutureSkewSeconds: -1"),
                change(POLICY, "invalid source", "VERIFIED_ATM_TERMINAL", "IP_ADDRESS"),
                change(POLICY, "numeric source", "[VERIFIED_ATM_TERMINAL,", "[0,"),
                change(POLICY, "source/channel mismatch", "eligibleChannels: [ATM, CARD_PRESENT]", "eligibleChannels: [ATM]"),
                change(POLICY, "online travel", "eligibleChannels: [ATM, CARD_PRESENT]", "eligibleChannels: [ATM, ECOMMERCE]"),
                change(POLICY, "duplicate source", "[VERIFIED_ATM_TERMINAL, VERIFIED_CARD_PRESENT_TERMINAL]", "[VERIFIED_ATM_TERMINAL, VERIFIED_ATM_TERMINAL]"),
                change(POLICY, "zero speed", "maxSpeedKmh: 1000", "maxSpeedKmh: 0"),
                change(POLICY, "nonfinite speed", "maxSpeedKmh: 1000", "maxSpeedKmh: .nan"),
                change(POLICY, "negative accuracy", "maxAccuracyKm: 5", "maxAccuracyKm: -1"),
                change(POLICY, "candidate overflow", "maxCandidates: 500", "maxCandidates: 2147483647"),
                change(POLICY, "contradictory thresholds", "smallPaymentMaximum: {ZAR: \"50.00\"}", "smallPaymentMaximum: {ZAR: \"5000.00\"}"),
                Arguments.of("multiple documents", POLICY + "---\nschemaVersion: 1\n"),
                Arguments.of("anchor", "schemaVersion: &version 1\n"),
                Arguments.of("alias", "key: *missing\n"),
                Arguments.of("explicit tag", "schemaVersion: !!int 1\n"),
                Arguments.of("merge", "<<: {schemaVersion: 1}\n"),
                Arguments.of("non-string key", "1: true\n"),
                Arguments.of("deep structure", "key: " + "[".repeat(40) + "1" + "]".repeat(40)),
                Arguments.of("oversized document", "#".repeat(1_048_577)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLimits")
    void rejectsInvalidOperationalRelationships(String description, String input) {
        assertThatThrownBy(() -> yaml.read(input, ServiceLimits.class)).isInstanceOf(RuntimeException.class);
    }

    static Stream<Arguments> invalidLimits() {
        return Stream.of(
                change(LIMITS, "schema", "schemaVersion: 1", "schemaVersion: 2"),
                Arguments.of("unknown setting", LIMITS + "typo: 1\n"),
                change(LIMITS, "missing field", "  maxBodyBytes: 16384", ""),
                change(LIMITS, "null group", "api:", "api: null\nunused:"),
                change(LIMITS, "body limit", "maxBodyBytes: 16384", "maxBodyBytes: 16385"),
                change(LIMITS, "rule limit", "maxRegisteredRules: 64", "maxRegisteredRules: 65"),
                change(LIMITS, "evidence limit", "maxEvidenceFactsPerRule: 16", "maxEvidenceFactsPerRule: 17"),
                change(LIMITS, "identifier limit", "maxIdentifierLength: 128", "maxIdentifierLength: 129"),
                change(LIMITS, "zero bound", "maxJsonDepth: 8", "maxJsonDepth: 0"),
                change(LIMITS, "page order", "defaultPageSize: 20", "defaultPageSize: 101"),
                change(LIMITS, "query range order", "defaultQueryRangeDays: 1", "defaultQueryRangeDays: 32"),
                change(LIMITS, "deadline order", "edgeDeadlineMillis: 10000", "edgeDeadlineMillis: 8000"),
                change(LIMITS, "work budget", "maxProcessingMillis: 6000", "maxProcessingMillis: 7600"),
                change(LIMITS, "lock ordering", "lockTimeoutMillis: 1000", "lockTimeoutMillis: 1501"),
                change(LIMITS, "Hikari validation bound", "poolAcquireTimeoutMillis: 500", "poolAcquireTimeoutMillis: 499"),
                change(LIMITS, "spare threads exceed worker limit", "httpMinSpareThreads: 2", "httpMinSpareThreads: 21"),
                change(LIMITS, "zero connection timeout", "httpConnectionTimeoutMillis: 5000", "httpConnectionTimeoutMillis: 0"),
                change(LIMITS, "zero pool", "dbPoolMaxConnectionsPerReplica: 10", "dbPoolMaxConnectionsPerReplica: 0"),
                change(LIMITS, "zero socket backlog", "maxQueuedRequestsPerReplica: 20", "maxQueuedRequestsPerReplica: 0"),
                change(LIMITS, "queue overflow", "maxQueuedRequestsPerReplica: 20", "maxQueuedRequestsPerReplica: 2147483647"),
                change(LIMITS, "negative clock tolerance", "maxClockRegressionMillis: 1000", "maxClockRegressionMillis: -1"),
                change(LIMITS, "shutdown order", "containerStopGraceSeconds: 45", "containerStopGraceSeconds: 30"),
                change(LIMITS, "shutdown budget", "gracefulShutdownSeconds: 30", "gracefulShutdownSeconds: 8"),
                change(LIMITS, "zero quota", "capacity: 30", "capacity: 0"),
                change(LIMITS, "quoted number", "maxJsonDepth: 8", "maxJsonDepth: \"8\""),
                change(LIMITS, "implicit octal", "maxJsonDepth: 8", "maxJsonDepth: 010"));
    }

    @Test
    void springRegistersSixthTypedRuleWithoutChangingTheLoader() {
        String withSixth = POLICY + "  testRule: {enabled: true, windowSeconds: 30}\n";
        new ApplicationContextRunner().withInitializer(new LimitsInitializer())
                .withPropertyValues("fraud.limits-location=classpath:config/service.example.yaml",
                        "fraud.deployment-stop-grace-seconds=45")
                .withUserConfiguration(PolicyConfiguration.class, SixthRuleConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RuleRegistry.class).definitions()).hasSize(6);
                    var snapshot = context.getBean(PolicyLoader.class).read(withSixth);
                    assertThat(snapshot.rules()).hasSize(6);
                    assertThat(snapshot.rules().get(new RuleId("TEST_RULE")).settings())
                            .isEqualTo(new TestSettings(true, 30));
                });
    }

    @Test
    void invalidStartupPolicyPreventsContextActivation() {
        new ApplicationContextRunner().withInitializer(new LimitsInitializer())
                .withPropertyValues("fraud.limits-location=classpath:config/service.example.yaml",
                        "fraud.deployment-stop-grace-seconds=45")
                .withBean(za.co.sibusiso.fraud.policy.ActivePolicyStore.class, () -> new za.co.sibusiso.fraud.policy.ActivePolicyStore() {
                    public Reference active() { return new Reference(1, "a".repeat(64)); }
                    public String document(String hash) { return POLICY.replace("schemaVersion: 1", "schemaVersion: 2"); }
                })
                .withUserConfiguration(PolicyConfiguration.class, za.co.sibusiso.fraud.policy.PolicyRuntimeConfiguration.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validatedOperationalSettingsOverrideConflictingFrameworkDefaults() throws IOException {
        var file = java.nio.file.Files.writeString(temporaryDirectory.resolve("limits.yaml"),
                LIMITS.replace("dbPoolMaxConnectionsPerReplica: 10", "dbPoolMaxConnectionsPerReplica: 3"));
        new ApplicationContextRunner().withInitializer(new LimitsInitializer())
                .withPropertyValues("fraud.limits-location=" + file.toUri(),
                        "spring.datasource.hikari.maximum-pool-size=99", "fraud.deployment-stop-grace-seconds=45")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ServiceLimits.class).operations().dbPoolMaxConnectionsPerReplica()).isEqualTo(3);
                    assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(3);
                });
    }

    @Test
    void deploymentGraceAndRemoteConfigurationAreNotSilentlyAccepted() {
        new ApplicationContextRunner().withInitializer(new LimitsInitializer())
                .withPropertyValues("fraud.limits-location=classpath:config/service.example.yaml", "fraud.deployment-stop-grace-seconds=30")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> StrictYaml.localResource(new org.springframework.core.io.DefaultResourceLoader(),
                "https://example.invalid/policy.yaml")).hasMessageContaining("classpath: or file:");
    }

    @Test
    void registryRejectsDuplicateKeysIdsAndConfiguredCapacityOverflow() {
        var high = BuiltInRules.highAmount();
        assertThatThrownBy(() -> RuleRegistry.of(List.of(high, high), 64)).hasMessageContaining("Duplicate");
        var duplicateId = new RuleDefinition<>("otherKey", high.id(), "1", high.settingsType(), high.validator());
        assertThatThrownBy(() -> RuleRegistry.of(List.of(high, duplicateId), 64)).hasMessageContaining("Duplicate rule ID");
        assertThatThrownBy(() -> RuleRegistry.of(BuiltInRules.definitions(), 4)).hasMessageContaining("configured limit");
    }

    record TestSettings(boolean enabled, int windowSeconds) implements RuleSettings {
        TestSettings { za.co.sibusiso.fraud.domain.ContractChecks.require(windowSeconds > 0, "Invalid test window"); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SixthRuleConfiguration {
        @Bean
        RuleDefinition<TestSettings> testRule() {
            return new RuleDefinition<>("testRule", new RuleId("TEST_RULE"), "1", TestSettings.class,
                    (settings, currencies) -> { });
        }
    }

    static String text(String location) {
        try (var stream = new ClassPathResource(location).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static Arguments change(String source, String name, String before, String after) {
        assertThat(source).contains(before);
        return Arguments.of(name, source.replaceFirst(java.util.regex.Pattern.quote(before),
                java.util.regex.Matcher.quoteReplacement(after)));
    }

    private PolicyLoader loader() { return new PolicyLoader(yaml, RuleRegistry.of(BuiltInRules.definitions(), 64)); }
}
