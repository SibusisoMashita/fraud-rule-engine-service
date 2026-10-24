package za.co.sibusiso.fraud.evaluation.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.domain.EvaluationOutcome;
import za.co.sibusiso.fraud.domain.Money;
import za.co.sibusiso.fraud.domain.RuleId;
import za.co.sibusiso.fraud.domain.RuleResult;
import za.co.sibusiso.fraud.domain.TransactionEvent;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.evaluation.history.LocationQuery;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.PolicySnapshot;
import za.co.sibusiso.fraud.policy.definition.RuleDefinition;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;
import za.co.sibusiso.fraud.policy.definition.RuleSettings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class PreparedPolicyTest {
    private final StrictYaml yaml = new StrictYaml();
    private final ServiceLimits limits =
            yaml.read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class);
    private final String disabledSource =
            yaml.readText(new ClassPathResource("config/policy.example.yaml"))
                    .replace("enabled: true", "enabled: false");
    private final TransactionEvent event =
            new TransactionEvent(
                    "event",
                    "customer",
                    "instrument",
                    TransactionEvent.Type.CARD_PURCHASE,
                    TransactionEvent.Status.BOOKED,
                    TransactionEvent.Channel.CARD_PRESENT,
                    Money.parse("ZAR", "15.00"),
                    "GROCERIES",
                    Instant.parse("2026-10-15T10:00:00Z"),
                    null);
    private final FraudRule.Context context = new FraudRule.Context(event.occurredAt(), 0);

    @Test
    void validatesConfigurationOnceAndReusesTheSameTypedSettingsForEveryOperation()
            throws Exception {
        var definition = definition("probeRule", "PROBE_RULE", "1");
        var rule = new ProbeRule(definition, new ArrayList<>());
        var snapshot = probePolicy(definition, "first");
        var prepared = engine(List.of(rule)).prepare(snapshot);
        var settings = snapshot.rules().get(definition.id()).settings();

        assertThat(prepared.snapshot()).isSameAs(snapshot);
        assertThat(prepared.historyQueries(event)).containsExactly(new HistoryQuery.Count(37));
        assertThat(prepared.locationQueries(event)).containsExactly(locationQuery(event));
        assertThat(prepared.smallPaymentsQueries(event)).containsExactly(smallPaymentsQuery(event));
        prepared.evaluate(event, context, limits.api());
        prepared.evaluate(event, context, limits.api());

        assertThat(rule.validated).hasSize(1);
        assertThat(rule.validated.getFirst()).isSameAs(settings);
        assertThat(rule.used).hasSize(5).allSatisfy(value -> assertThat(value).isSameAs(settings));
    }

    @Test
    void preparingANewerPolicyCannotChangeTheCapturedThresholdHashOrResults() throws Exception {
        var engine = engine(List.of(new HighAmountRule()));
        String source = highAmountOnlySource();
        var firstSnapshot = policy(List.of(), "", source.replace("25000.00", "10.00"));
        var secondSnapshot = policy(List.of(), "", source.replace("25000.00", "20.00"));
        var first = engine.prepare(firstSnapshot);
        var second = engine.prepare(secondSnapshot);

        var firstOutcome = first.evaluate(event, context, limits.api());
        var secondOutcome = second.evaluate(event, context, limits.api());

        assertThat(first.snapshot()).isSameAs(firstSnapshot);
        assertThat(second.snapshot()).isSameAs(secondSnapshot);
        assertThat(first.snapshot().policyHash()).isNotEqualTo(second.snapshot().policyHash());
        assertThat(firstOutcome.decision()).isEqualTo(EvaluationOutcome.Decision.FLAGGED);
        assertThat(secondOutcome.decision()).isEqualTo(EvaluationOutcome.Decision.CLEAR);
        assertThat(
                        ((RuleResult.Number) highAmount(firstOutcome).evidence().get("threshold"))
                                .value())
                .isEqualByComparingTo("10.00");
        assertThat(
                        ((RuleResult.Number) highAmount(secondOutcome).evidence().get("threshold"))
                                .value())
                .isEqualByComparingTo("20.00");
    }

    @Test
    void disabledRulesNeedNoImplementationAndNeverValidatePlanOrEvaluate() throws Exception {
        var disabled = spy(new ImpossibleTravelRule(1));
        var engine = engine(List.of(new HighAmountRule(), disabled));
        clearInvocations(disabled);
        var prepared = engine.prepare(policy(List.of(), "", highAmountOnlySource()));

        assertThat(prepared.historyQueries(event)).isEmpty();
        assertThat(prepared.locationQueries(event)).isEmpty();
        assertThat(prepared.smallPaymentsQueries(event)).isEmpty();
        var result = prepared.evaluate(event, context, limits.api());

        verifyNoInteractions(disabled);
        assertThat(result.ruleResults()).hasSize(5);
        assertThat(
                        result.ruleResults().stream()
                                .filter(r -> r.status() == RuleResult.Status.DISABLED))
                .hasSize(4)
                .allSatisfy(
                        r -> {
                            assertThat(r.reasonCode()).isEqualTo("RULE_DISABLED");
                            assertThat(r.evidence())
                                    .containsOnlyKeys(RuleResult.EVIDENCE_SCHEMA_VERSION_KEY);
                        });
        assertThat(result.coverage()).isEqualTo(EvaluationOutcome.Coverage.COMPLETE);
    }

    @Test
    void anEnabledRuleWithoutAnImplementationCannotBePrepared() {
        var definition = definition("probeRule", "PROBE_RULE", "1");
        assertThatThrownBy(() -> engine(List.of()).prepare(probePolicy(definition, "first")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void anAlgorithmVersionMismatchFailsBeforeConfigurationValidation() {
        var configured = definition("probeRule", "PROBE_RULE", "1");
        var implementation =
                new ProbeRule(definition("probeRule", "PROBE_RULE", "2"), new ArrayList<>());
        assertThatThrownBy(
                        () ->
                                engine(List.of(implementation))
                                        .prepare(probePolicy(configured, "first")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
        assertThat(implementation.validated).isEmpty();
        assertThat(implementation.used).isEmpty();
    }

    @Test
    void aSettingsTypeMismatchFailsBeforeCastingOrConfigurationValidation() {
        var configured =
                new RuleDefinition<>(
                        "probeRule",
                        new RuleId("PROBE_RULE"),
                        "1",
                        OtherSettings.class,
                        (settings, currencies) -> {});
        var implementation =
                new ProbeRule(definition("probeRule", "PROBE_RULE", "1"), new ArrayList<>());
        assertThatThrownBy(
                        () ->
                                engine(List.of(implementation))
                                        .prepare(probePolicy(configured, "first")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
        assertThat(implementation.validated).isEmpty();
        assertThat(implementation.used).isEmpty();
    }

    @Test
    void infrastructureCompatibilityAndEvidenceCapacityAreCheckedDuringPreparation() {
        String source =
                highAmountOnlySource()
                        .replace(
                                "impossibleTravel:\n    enabled: false",
                                "impossibleTravel:\n    enabled: true");
        assertThatThrownBy(
                        () ->
                                engine(List.of(new HighAmountRule(), new ImpossibleTravelRule(1)))
                                        .prepare(policy(List.of(), "", source)))
                .hasMessageContaining("infrastructure safety ceiling");
        assertThatThrownBy(
                        () ->
                                new RuleEngine(List.of(new HighAmountRule()), 3)
                                        .prepare(policy(List.of(), "", highAmountOnlySource())))
                .hasMessageContaining("evidence limit");
    }

    @Test
    void ruleOrderAndDeduplicatedImmutableQueriesDoNotDependOnRegistrationOrder() throws Exception {
        var first = definition("firstRule", "A_PROBE", "1");
        var last = definition("lastRule", "Z_PROBE", "1");
        var invocationOrder = new ArrayList<String>();
        var prepared =
                engine(
                                List.of(
                                        new ProbeRule(last, invocationOrder),
                                        new ProbeRule(first, invocationOrder)))
                        .prepare(
                                policy(
                                        List.of(last, first),
                                        """
                          lastRule: {enabled: true, marker: last}
                          firstRule: {enabled: true, marker: first}
                        """,
                                        disabledSource));

        var aggregates = prepared.historyQueries(event);
        var locations = prepared.locationQueries(event);
        var smallPayments = prepared.smallPaymentsQueries(event);
        assertThat(aggregates).containsExactly(new HistoryQuery.Count(37));
        assertThat(locations).containsExactly(locationQuery(event));
        assertThat(smallPayments).containsExactly(smallPaymentsQuery(event));
        assertThatThrownBy(aggregates::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(locations::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(smallPayments::clear).isInstanceOf(UnsupportedOperationException.class);
        var result = prepared.evaluate(event, context, limits.api());
        assertThat(invocationOrder).containsExactly("A_PROBE", "Z_PROBE");
        assertThat(result.ruleResults())
                .extracting(r -> r.ruleId().value())
                .containsExactly(
                        "A_PROBE",
                        "CUMULATIVE_SPEND",
                        "HIGH_AMOUNT",
                        "IMPOSSIBLE_TRAVEL",
                        "SMALL_PAYMENT_BURST_THEN_LARGE",
                        "TRANSACTION_VELOCITY",
                        "Z_PROBE");
    }

    private RuleEngine engine(List<FraudRule<?>> implementations) {
        return new RuleEngine(implementations, limits.api().maxEvidenceFactsPerRule());
    }

    private String highAmountOnlySource() {
        return disabledSource.replace(
                "highAmount:\n    enabled: false", "highAmount:\n    enabled: true");
    }

    private PolicySnapshot probePolicy(RuleDefinition<?> definition, String marker) {
        return policy(
                List.of(definition),
                "  "
                        + definition.configurationKey()
                        + ": {enabled: true, marker: "
                        + marker
                        + "}\n",
                disabledSource);
    }

    private PolicySnapshot policy(
            List<RuleDefinition<?>> extraDefinitions, String extraYaml, String source) {
        var definitions = new ArrayList<>(BuiltInRules.definitions());
        definitions.addAll(extraDefinitions);
        return new PolicyLoader(
                        yaml, RuleRegistry.of(definitions, limits.api().maxRegisteredRules()))
                .read(source + extraYaml);
    }

    private static RuleDefinition<ProbeSettings> definition(String key, String id, String version) {
        return new RuleDefinition<>(
                key, new RuleId(id), version, ProbeSettings.class, (settings, currencies) -> {});
    }

    private static RuleResult highAmount(EvaluationOutcome outcome) {
        return outcome.ruleResults().stream()
                .filter(r -> r.ruleId().value().equals("HIGH_AMOUNT"))
                .findFirst()
                .orElseThrow();
    }

    private static LocationQuery locationQuery(TransactionEvent event) {
        return new LocationQuery(
                event.instrumentId(),
                event.occurredAt(),
                3600,
                10,
                new BigDecimal("5"),
                List.of(TransactionEvent.LocationSource.VERIFIED_CARD_PRESENT_TERMINAL),
                List.of(TransactionEvent.Channel.CARD_PRESENT));
    }

    private static SmallPaymentsQuery smallPaymentsQuery(TransactionEvent event) {
        return new SmallPaymentsQuery(
                event.occurredAt(), 900, event.money().currency(), new BigDecimal("50.00"));
    }

    record ProbeSettings(boolean enabled, String marker) implements RuleSettings {}

    record OtherSettings(boolean enabled, String marker) implements RuleSettings {}

    private static final class ProbeRule implements FraudRule<ProbeSettings> {
        private final RuleDefinition<ProbeSettings> definition;
        private final List<String> invocationOrder;
        private final List<ProbeSettings> validated = new ArrayList<>();
        private final List<ProbeSettings> used = new ArrayList<>();

        private ProbeRule(RuleDefinition<ProbeSettings> definition, List<String> invocationOrder) {
            this.definition = definition;
            this.invocationOrder = invocationOrder;
        }

        @Override
        public RuleDefinition<ProbeSettings> definition() {
            return definition;
        }

        @Override
        public int maximumEvidenceFacts() {
            return 2;
        }

        @Override
        public void validateConfiguration(ProbeSettings settings) {
            validated.add(settings);
        }

        @Override
        public Set<HistoryQuery> historyQueries(TransactionEvent event, ProbeSettings settings) {
            used.add(settings);
            return Set.of(new HistoryQuery.Count(37));
        }

        @Override
        public Optional<LocationQuery> locationQuery(
                TransactionEvent event, ProbeSettings settings) {
            used.add(settings);
            return Optional.of(PreparedPolicyTest.locationQuery(event));
        }

        @Override
        public Optional<SmallPaymentsQuery> smallPaymentsQuery(
                TransactionEvent event, ProbeSettings settings) {
            used.add(settings);
            return Optional.of(PreparedPolicyTest.smallPaymentsQuery(event));
        }

        @Override
        public RuleResult evaluate(
                TransactionEvent event, ProbeSettings settings, Context context) {
            used.add(settings);
            invocationOrder.add(definition.id().value());
            return new RuleResult(
                    definition.id(),
                    definition.algorithmVersion(),
                    RuleResult.Status.NO_MATCH,
                    "PROBE_NO_MATCH",
                    Map.of(
                            RuleResult.EVIDENCE_SCHEMA_VERSION_KEY,
                            new RuleResult.Number(BigDecimal.ONE),
                            "marker",
                            new RuleResult.Text(settings.marker())));
        }
    }
}
