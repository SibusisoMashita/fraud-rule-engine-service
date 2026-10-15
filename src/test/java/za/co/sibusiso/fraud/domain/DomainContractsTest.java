package za.co.sibusiso.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import za.co.sibusiso.fraud.domain.TransactionEvent.*;

import static org.assertj.core.api.Assertions.*;

class DomainContractsTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "+1", "01", ".5", "1.", "1e2", "NaN", "Infinity",
            "1.00001", "10000000000000000", " 1", "1 ", ""})
    void rejectsUnsafeMoneyRepresentations(String value) {
        assertThatThrownBy(() -> Money.parse("ZAR", value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesExactDecimalValuesAtStorageBoundaries() {
        assertThat(Money.parse("ZAR", "9999999999999999.9999").amount())
                .isEqualByComparingTo("9999999999999999.9999");
        assertThat(Money.parse("ZAR", "0.0001").amount()).isEqualByComparingTo("0.0001");
        assertThat(Money.parse("ZAR", "0.10").amount().add(Money.parse("ZAR", "0.20").amount()))
                .isEqualByComparingTo("0.30");
        assertThatThrownBy(() -> new Money("ZAR", new BigDecimal("1E+1000000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("zar", "1.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesOffsetsWithoutLosingMicrosecondsOrInferringLocalTime() {
        assertThat(EventTime.parse("2026-10-15T00:30:00.123456+02:00"))
                .isEqualTo(Instant.parse("2026-10-14T22:30:00.123456Z"));
        assertThat(EventTime.parse("2024-02-29T12:00:00Z"))
                .isEqualTo(Instant.parse("2024-02-29T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-15T12:00:00", "2026-10-15", "2026-02-29T12:00:00Z",
            "2026-10-15T12:00:00.1234567Z", "2026-10-15T12:00:60Z", "2026-10-15T12:00:00+19:00",
            "0000-01-01T00:00:00Z", "0001-01-01T00:00:00+02:00", "9999-12-31T23:59:59-02:00"})
    void rejectsUnrepresentableOrAmbiguousTimes(String value) {
        assertThatThrownBy(() -> EventTime.parse(value)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void enforcesTransactionAndPhysicalEvidenceConsistency() {
        assertThatThrownBy(() -> event(Type.ATM_WITHDRAWAL, Channel.CARD_PRESENT, null))
                .hasMessageContaining("type and channel");
        assertThatThrownBy(() -> event(Type.CARD_PURCHASE, Channel.ATM, null))
                .hasMessageContaining("type and channel");
        var atm = location("-26.2041", "28.0473", "0.1", LocationSource.VERIFIED_ATM_TERMINAL);
        assertThatThrownBy(() -> event(Type.CARD_PURCHASE, Channel.ECOMMERCE, atm))
                .hasMessageContaining("physical presence");
        assertThatThrownBy(() -> event(Type.CARD_PURCHASE, Channel.CARD_PRESENT, atm))
                .hasMessageContaining("source and channel");
        assertThat(event(Type.ATM_WITHDRAWAL, Channel.ATM, null).physicalLocation()).isNull();
        assertThat(event(Type.ATM_WITHDRAWAL, Channel.ATM, atm).physicalLocation()).isEqualTo(atm);
        assertThat(location("90", "-180", "0", LocationSource.VERIFIED_ATM_TERMINAL)).isNotNull();
        assertThatThrownBy(() -> location("90.0001", "0", "0", LocationSource.VERIFIED_ATM_TERMINAL))
                .hasMessageContaining("Latitude");
        assertThatThrownBy(() -> location("0", "180.0001", "0", LocationSource.VERIFIED_ATM_TERMINAL))
                .hasMessageContaining("Longitude");
        assertThatThrownBy(() -> location("0", "0", "-0.1", LocationSource.VERIFIED_ATM_TERMINAL))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> location("0", "0", "1E+1000000", LocationSource.VERIFIED_ATM_TERMINAL))
                .hasMessageContaining("too large");
    }

    @Test
    void validatesIdentifiersAndPreservesTextWithoutImplicitTrimming() {
        assertThat(ContractChecks.identifier("a".repeat(128))).hasSize(128);
        assertThatThrownBy(() -> ContractChecks.identifier("a".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractChecks.identifier("bad/id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractChecks.text("bad\ncategory", 64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContractChecks.text("\uD800", 64)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContractChecks.text(" Café ", 64)).isEqualTo(" Café ");
        assertThat(ContractChecks.text("😀", 1)).isEqualTo("😀");
    }

    @ParameterizedTest
    @CsvSource({
            "NO_MATCH,NO_MATCH,CLEAR,COMPLETE",
            "NO_MATCH,NOT_APPLICABLE,CLEAR,COMPLETE",
            "NOT_APPLICABLE,NOT_APPLICABLE,CLEAR,COMPLETE",
            "INSUFFICIENT_DATA,NO_MATCH,INDETERMINATE,PARTIAL",
            "INSUFFICIENT_DATA,INSUFFICIENT_DATA,INDETERMINATE,PARTIAL",
            "MATCH,NO_MATCH,FLAGGED,COMPLETE",
            "MATCH,NOT_APPLICABLE,FLAGGED,COMPLETE",
            "MATCH,INSUFFICIENT_DATA,FLAGGED,PARTIAL",
            "MATCH,MATCH,FLAGGED,COMPLETE",
            "DISABLED,NO_MATCH,CLEAR,COMPLETE",
            "DISABLED,INSUFFICIENT_DATA,INDETERMINATE,PARTIAL",
            "DISABLED,MATCH,FLAGGED,COMPLETE"
    })
    void implementsThePublishedOutcomeTable(RuleResult.Status first, RuleResult.Status second,
            EvaluationOutcome.Decision decision, EvaluationOutcome.Coverage coverage) {
        var a = result("A", first);
        var b = result("B", second);
        var expected = Map.of(a.ruleId(), new EvaluationOutcome.ExpectedRule("1", first != RuleResult.Status.DISABLED),
                b.ruleId(), new EvaluationOutcome.ExpectedRule("1", second != RuleResult.Status.DISABLED));
        var outcome = EvaluationOutcome.aggregate(expected, List.of(b, a), 64, 16);
        assertThat(outcome.decision()).isEqualTo(decision);
        assertThat(outcome.coverage()).isEqualTo(coverage);
        assertThat(outcome.ruleResults()).containsExactly(a, b);
        assertThat(EvaluationOutcome.aggregate(expected, List.of(a, b), 64, 16)).isEqualTo(outcome);
    }

    @Test
    void rejectsIncompleteDuplicateDisabledAndInconsistentResults() {
        var a = result("A", RuleResult.Status.NO_MATCH);
        var expected = Map.of(a.ruleId(), new EvaluationOutcome.ExpectedRule("1", true));
        assertThatThrownBy(() -> EvaluationOutcome.aggregate(expected, List.of(), 64, 16)).hasMessageContaining("incomplete");
        assertThatThrownBy(() -> EvaluationOutcome.aggregate(expected, List.of(result("B", RuleResult.Status.MATCH)), 64, 16))
                .hasMessageContaining("Unexpected");
        assertThatThrownBy(() -> EvaluationOutcome.aggregate(Map.of(a.ruleId(), new EvaluationOutcome.ExpectedRule("2", true)),
                List.of(a), 64, 16)).hasMessageContaining("algorithm");
        assertThatThrownBy(() -> EvaluationOutcome.aggregate(Map.of(a.ruleId(), new EvaluationOutcome.ExpectedRule("1", false)),
                List.of(a), 64, 16)).hasMessageContaining("enabled");
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Decision.CLEAR,
                EvaluationOutcome.Coverage.COMPLETE, List.of(a, a))).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Decision.CLEAR,
                EvaluationOutcome.Coverage.COMPLETE, List.of(result("A", RuleResult.Status.DISABLED))))
                .hasMessageContaining("All-disabled");
        assertThatThrownBy(() -> new EvaluationOutcome(EvaluationOutcome.Decision.FLAGGED,
                EvaluationOutcome.Coverage.COMPLETE, List.of(a))).hasMessageContaining("disagree");
    }

    @Test
    void evidenceIsVersionedImmutableAndBoundedWithoutClosingTheRuleIdSet() {
        var facts = new java.util.HashMap<String, RuleResult.Fact>();
        facts.put("evidenceSchemaVersion", new RuleResult.Number(BigDecimal.ONE));
        var result = new RuleResult(new RuleId("FUTURE_SIXTH_RULE"), "2", RuleResult.Status.MATCH, "TEST", facts);
        facts.put("mutated", new RuleResult.Flag(true));
        assertThat(result.evidence()).hasSize(1);
        assertThatThrownBy(() -> result.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RuleResult(result.ruleId(), "1", result.status(), "TEST", Map.of()))
                .hasMessageContaining("schema version");
        assertThatThrownBy(() -> new RuleResult.Text("x".repeat(257))).hasMessageContaining("text length");
        assertThat(new RuleResult.Text("😀".repeat(256)).value().codePointCount(0, 512)).isEqualTo(256);
        assertThatThrownBy(() -> new RuleResult.Text("\uD800")).hasMessageContaining("Unicode");
        for (int i = 0; i < 16; i++) facts.put("fact" + i, new RuleResult.Flag(true));
        assertThatThrownBy(() -> new RuleResult(result.ruleId(), "1", result.status(), "TEST", facts))
                .hasMessageContaining("Too many");
    }

    private static RuleResult result(String id, RuleResult.Status status) {
        return new RuleResult(new RuleId(id), "1", status, "TEST",
                Map.of("evidenceSchemaVersion", new RuleResult.Number(BigDecimal.ONE)));
    }

    private static TransactionEvent event(Type type, Channel channel, PhysicalLocation location) {
        return new TransactionEvent("event-1", "customer-1", "card-1", type, Status.BOOKED, channel,
                Money.parse("ZAR", "100.00"), "GROCERIES", EventTime.parse("2026-10-15T12:00:00Z"), location);
    }

    private static PhysicalLocation location(String latitude, String longitude, String accuracy, LocationSource source) {
        return new PhysicalLocation(new BigDecimal(latitude), new BigDecimal(longitude), new BigDecimal(accuracy), source, null);
    }
}
