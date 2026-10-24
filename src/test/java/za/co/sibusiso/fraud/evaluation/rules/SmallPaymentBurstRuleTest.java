package za.co.sibusiso.fraud.evaluation.rules;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.*;
import za.co.sibusiso.fraud.evaluation.history.HistoryFacts;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsHistory;
import za.co.sibusiso.fraud.evaluation.history.SmallPaymentsQuery;
import za.co.sibusiso.fraud.policy.definition.BuiltInRuleSettings.SmallPaymentBurst;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyAmount;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class SmallPaymentBurstRuleTest {
    private final SmallPaymentBurstRule rule = new SmallPaymentBurstRule();
    private final StrictYaml yaml = new StrictYaml();
    private final ServiceLimits limits =
            yaml.read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class);
    private final Instant now = Instant.parse("2026-10-16T00:00:00Z");

    private SmallPaymentBurst settings(
            String small, String large, int minimum, int window, boolean enabled) {
        return new SmallPaymentBurst(
                enabled,
                window,
                minimum,
                Map.of("ZAR", new PolicyAmount(small)),
                Map.of("ZAR", new PolicyAmount(large)));
    }

    private TransactionEvent event(String amount) {
        return new TransactionEvent(
                "current",
                "customer",
                "card",
                TransactionEvent.Type.CARD_PURCHASE,
                TransactionEvent.Status.BOOKED,
                TransactionEvent.Channel.ECOMMERCE,
                Money.parse("ZAR", amount),
                "SYNTHETIC",
                now,
                null);
    }

    @ParameterizedTest
    @CsvSource({
        "99.99,9,NO_MATCH",
        "100.00,9,NO_MATCH",
        "100.01,2,NO_MATCH",
        "100.01,3,MATCH",
        "100.01,4,MATCH",
        "100.01,0,NO_MATCH"
    })
    void strictLargeAmountAndInclusiveMinimumCountUseOnlySuppliedPolicy(
            String amount, long count, RuleResult.Status expected) {
        var policy = settings("10.00", "100.00", 3, 37, true);
        var input = event(amount);
        var query = rule.smallPaymentsQuery(input, policy);
        var context =
                query.isPresent()
                        ? new FraudRule.Context(
                                now,
                                11,
                                new HistoryFacts(
                                        Map.of(),
                                        Map.of(),
                                        Map.of(
                                                query.orElseThrow(),
                                                new SmallPaymentsHistory(
                                                        count,
                                                        BigDecimal.valueOf(count)
                                                                .multiply(
                                                                        new BigDecimal("0.10"))))))
                        : new FraudRule.Context(now, 11);
        var result = rule.evaluate(input, policy, context);
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.evidence().get("windowSeconds"))
                .isEqualTo(new RuleResult.Number(BigDecimal.valueOf(37)));
        assertThat(result.evidence().get("minSmallPayments"))
                .isEqualTo(new RuleResult.Number(BigDecimal.valueOf(3)));
        assertThat(result.evidence()).hasSize(query.isPresent() ? rule.maximumEvidenceFacts() : 8);
        if (query.isEmpty()) assertThat(result.evidence()).doesNotContainKeys("count", "total");
    }

    @Test
    void declaresExactEventTimeCurrencyWindowAndSmallMaximumOnlyForALargePayment() {
        var policy = settings("0.17", "0.31", 2, 23, true);
        assertThat(rule.smallPaymentsQuery(event("0.31"), policy)).isEmpty();
        assertThat(rule.smallPaymentsQuery(event("0.32"), policy))
                .contains(new SmallPaymentsQuery(now, 23, "ZAR", new BigDecimal("0.17")));
        assertThatThrownBy(
                        () -> rule.evaluate(event("0.32"), policy, new FraudRule.Context(now, 0)))
                .hasMessageContaining("not loaded");
    }

    @Test
    void disabledRuleRequestsNoHistoryAndAnInadequateEvidenceBudgetIsRejected() throws Exception {
        String source =
                yaml.readText(new ClassPathResource("config/policy.example.yaml"))
                        .replace(
                                "transactionVelocity:\n    enabled: true",
                                "transactionVelocity:\n    enabled: false")
                        .replace(
                                "cumulativeSpend:\n    enabled: true",
                                "cumulativeSpend:\n    enabled: false")
                        .replace(
                                "impossibleTravel:\n    enabled: true",
                                "impossibleTravel:\n    enabled: false");
        var loader =
                new PolicyLoader(
                        yaml,
                        RuleRegistry.of(
                                BuiltInRules.definitions(), limits.api().maxRegisteredRules()));
        var engine =
                new RuleEngine(
                        List.of(new HighAmountRule(), rule),
                        limits.api().maxEvidenceFactsPerRule());
        var policy =
                loader.read(
                        source.replace(
                                "smallPaymentBurstThenLarge:\n    enabled: true",
                                "smallPaymentBurstThenLarge:\n    enabled: false"));
        assertThat(engine.prepare(policy).smallPaymentsQueries(event("6000.00"))).isEmpty();
        var result =
                engine.prepare(policy)
                        .evaluate(event("6000.00"), new FraudRule.Context(now, 0), limits.api());
        assertThat(result.ruleResults())
                .filteredOn(r -> r.ruleId().equals(rule.definition().id()))
                .singleElement()
                .extracting(RuleResult::status)
                .isEqualTo(RuleResult.Status.DISABLED);
        assertThatThrownBy(
                        () ->
                                new RuleEngine(List.of(new HighAmountRule(), rule), 9)
                                        .verify(loader.read(source)))
                .hasMessageContaining("evidence limit");
    }
}
