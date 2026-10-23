package za.co.sibusiso.fraud.evaluation.rules;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.evaluation.history.HistoryFacts;
import za.co.sibusiso.fraud.evaluation.history.HistoryQuery;
import za.co.sibusiso.fraud.policy.definition.BuiltInRuleSettings.*;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyAmount;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class HistoricalRulesTest {
    private final Instant now = Instant.parse("2026-10-15T10:00:00Z");
    private final TransactionEvent event =
            new TransactionEvent(
                    "event",
                    "customer",
                    "card",
                    TransactionEvent.Type.CARD_PURCHASE,
                    TransactionEvent.Status.BOOKED,
                    TransactionEvent.Channel.ECOMMERCE,
                    Money.parse("USD", "0.01"),
                    "GROCERIES",
                    now,
                    null);

    @ParameterizedTest
    @CsvSource({"0,1,NO_MATCH", "1,2,NO_MATCH", "2,2,MATCH", "9,12,NO_MATCH", "9,9,MATCH"})
    void velocityAddsCurrentEventExactlyOnceAndUsesConfiguredLimit(
            long previous, int maximum, RuleResult.Status expected) {
        var settings = new Velocity(true, 17, maximum);
        var rule = new TransactionVelocityRule();
        var context =
                context(
                        new HistoryQuery.Count(settings.windowSeconds()),
                        BigDecimal.valueOf(previous));
        var result = rule.evaluate(event, settings, context);
        assertThat(result.status()).isEqualTo(expected);
        assertThat(number(result, "count")).isEqualByComparingTo(BigDecimal.valueOf(previous + 1));
        assertThat(number(result, "maxTransactions"))
                .isEqualByComparingTo(BigDecimal.valueOf(maximum));
        assertThat(rule.historyQueries(event, settings))
                .containsExactly(new HistoryQuery.Count(17));
    }

    @ParameterizedTest
    @CsvSource({
        "9.99,0.01,10.00,NO_MATCH",
        "9.99,0.02,10.00,MATCH",
        "0,0.10,0.10,NO_MATCH",
        "0.10,0.20,0.30,NO_MATCH",
        "1000000000000.0001,0.0001,1000000000000.0001,MATCH"
    })
    void spendUsesExactDecimalsCurrentCurrencyAndConfiguredThreshold(
            String prior, String amount, String threshold, RuleResult.Status expected) {
        var settings = new CumulativeSpend(true, 23, Map.of("USD", new PolicyAmount(threshold)));
        var input =
                new TransactionEvent(
                        event.eventId(),
                        event.customerId(),
                        event.instrumentId(),
                        event.transactionType(),
                        event.status(),
                        event.channel(),
                        Money.parse("USD", amount),
                        event.category(),
                        event.occurredAt(),
                        null);
        var rule = new CumulativeSpendRule();
        var context =
                context(
                        new HistoryQuery.Spend(settings.windowSeconds(), "USD"),
                        new BigDecimal(prior));
        var result = rule.evaluate(input, settings, context);
        assertThat(result.status()).isEqualTo(expected);
        assertThat(number(result, "total"))
                .isEqualByComparingTo(new BigDecimal(prior).add(new BigDecimal(amount)));
        assertThat(number(result, "threshold")).isEqualByComparingTo(threshold);
        assertThat(rule.historyQueries(input, settings))
                .containsExactly(new HistoryQuery.Spend(23, "USD"));
    }

    @Test
    void enabledRulesDeclareOnlyTheirCapturedPolicyWindowsAndDisabledRulesNeedNoHistory() {
        var yaml = new StrictYaml();
        var limits =
                yaml.read(
                        new ClassPathResource("config/service.example.yaml"), ServiceLimits.class);
        var loader =
                new PolicyLoader(
                        yaml,
                        RuleRegistry.of(
                                BuiltInRules.definitions(), limits.api().maxRegisteredRules()));
        String source =
                yaml.readText(new ClassPathResource("config/policy.example.yaml"))
                        .replace(
                                "impossibleTravel:\n    enabled: true",
                                "impossibleTravel:\n    enabled: false")
                        .replace(
                                "smallPaymentBurstThenLarge:\n    enabled: true",
                                "smallPaymentBurstThenLarge:\n    enabled: false");
        var engine =
                new RuleEngine(
                        List.of(
                                new HighAmountRule(),
                                new TransactionVelocityRule(),
                                new CumulativeSpendRule()),
                        limits.api().maxEvidenceFactsPerRule());
        assertThat(
                        engine.historyQueries(
                                event,
                                loader.read(
                                        source.replace("windowSeconds: 600", "windowSeconds: 37")
                                                .replace(
                                                        "windowSeconds: 1800",
                                                        "windowSeconds: 59"))))
                .containsExactlyInAnyOrder(
                        new HistoryQuery.Count(37), new HistoryQuery.Spend(59, "USD"));
        String velocityOff =
                source.replace(
                        "transactionVelocity:\n    enabled: true",
                        "transactionVelocity:\n    enabled: false");
        assertThat(engine.historyQueries(event, loader.read(velocityOff)))
                .containsExactly(new HistoryQuery.Spend(1800, "USD"));
        String bothOff =
                velocityOff.replace(
                        "cumulativeSpend:\n    enabled: true",
                        "cumulativeSpend:\n    enabled: false");
        assertThat(engine.historyQueries(event, loader.read(bothOff))).isEmpty();
    }

    @Test
    void missingHistoryIsAFailureAndCannotBecomeAZeroValueDecision() {
        var empty = new FraudRule.Context(now, 0);
        assertThatThrownBy(
                        () ->
                                new TransactionVelocityRule()
                                        .evaluate(event, new Velocity(true, 17, 3), empty))
                .hasMessageContaining("not loaded");
        assertThatThrownBy(
                        () ->
                                new CumulativeSpendRule()
                                        .evaluate(
                                                event,
                                                new CumulativeSpend(
                                                        true,
                                                        23,
                                                        Map.of("USD", new PolicyAmount("1.00"))),
                                                empty))
                .hasMessageContaining("not loaded");
        var overflow = context(new HistoryQuery.Count(17), BigDecimal.valueOf(Long.MAX_VALUE));
        assertThatThrownBy(
                        () ->
                                new TransactionVelocityRule()
                                        .evaluate(event, new Velocity(true, 17, 3), overflow))
                .isInstanceOf(ArithmeticException.class);
    }

    private FraudRule.Context context(HistoryQuery query, BigDecimal previous) {
        return new FraudRule.Context(now, 7, new HistoryFacts(Map.of(query, previous)));
    }

    private BigDecimal number(RuleResult result, String key) {
        return ((RuleResult.Number) result.evidence().get(key)).value();
    }
}
