package za.co.sibusiso.fraud.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.domain.*;
import static org.assertj.core.api.Assertions.*;

class RuleEngineTest {
    private final StrictYaml yaml=new StrictYaml();
    private final String source=yaml.readText(new ClassPathResource("config/policy.example.yaml"));
    private final ServiceLimits limits=yaml.read(new ClassPathResource("config/service.example.yaml"),ServiceLimits.class);
    private final PolicyLoader loader=new PolicyLoader(yaml,RuleRegistry.of(BuiltInRules.definitions(),limits.api().maxRegisteredRules()));

    @ParameterizedTest
    @CsvSource({"9.99,10.00,CLEAR","10.00,10.00,CLEAR","10.01,10.00,FLAGGED","10.01,20.00,CLEAR","0.01,0.01,CLEAR","0.02,0.01,FLAGGED"})
    void highAmountUsesOnlySuppliedPolicyAndStrictDecimalComparison(String amount,String threshold,String decision) throws Exception {
        var policy=loader.read(source.replace("25000.00",threshold));
        var engine=new RuleEngine(List.of(new HighAmountRule()), limits.api().maxEvidenceFactsPerRule());
        var outcome=engine.evaluate(event(amount),policy,new FraudRule.Context(Instant.now(),0),limits.api());
        assertThat(outcome.decision().name()).isEqualTo(decision);
        assertThat(outcome.coverage()).isEqualTo(EvaluationOutcome.Coverage.COMPLETE);
        var high=outcome.ruleResults().stream().filter(r->r.ruleId().value().equals("HIGH_AMOUNT")).findFirst().orElseThrow();
        assertThat(((RuleResult.Number)high.evidence().get("threshold")).value()).isEqualByComparingTo(threshold);
        assertThat(outcome.ruleResults().stream().filter(r->r.status()==RuleResult.Status.DISABLED)).hasSize(4);
    }
    @Test void anEvidenceBudgetTooSmallForTheEnabledSchemaIsRejectedBeforeProcessing() {
        assertThatThrownBy(()->new RuleEngine(List.of(new HighAmountRule()), 3).verify(loader.read(source)))
                .hasMessageContaining("evidence limit");
    }
    @Test void enablingAnUnimplementedRuleFailsInsteadOfSavingAFabricatedNonmatch() {
        var policy=loader.read(source.replace("transactionVelocity:\n    enabled: false","transactionVelocity:\n    enabled: true"));
        assertThatThrownBy(()->new RuleEngine(List.of(new HighAmountRule()), limits.api().maxEvidenceFactsPerRule()).verify(policy)).hasMessageContaining("not supported");
    }
    @Test void sixthSpringRuleExecutesWithoutChangingOrchestrationAndDisabledHighAmountIsNotCalled() {
        new ApplicationContextRunner().withInitializer(new LimitsInitializer())
                .withPropertyValues("fraud.limits-location=classpath:config/service.example.yaml")
                .withUserConfiguration(PolicyConfiguration.class,EvaluationConfiguration.class,SixthRule.class)
                .run(context->{
                    assertThat(context).hasNotFailed();
                    var policy=context.getBean(PolicyLoader.class).read(source.replaceFirst("enabled: true","enabled: false")
                            +"  testRule: {enabled: true, marker: configured}\n");
                    var outcome=context.getBean(RuleEngine.class).evaluate(event("10.01"),policy,
                            new FraudRule.Context(Instant.now(),0),limits.api());
                    assertThat(outcome.ruleResults()).hasSize(6);
                    assertThat(outcome.decision()).isEqualTo(EvaluationOutcome.Decision.FLAGGED);
                    assertThat(outcome.ruleResults().stream().filter(r->r.ruleId().value().equals("HIGH_AMOUNT")).findFirst().orElseThrow().status())
                            .isEqualTo(RuleResult.Status.DISABLED);
                });
    }
    record TestSettings(boolean enabled,String marker) implements RuleSettings { }
    @TestConfiguration(proxyBeanMethods=false)
    static class SixthRule {
        @Bean RuleDefinition<TestSettings> testDefinition() {
            return new RuleDefinition<>("testRule",new RuleId("TEST_RULE"),"1",TestSettings.class,(settings,currencies)->{});
        }
        @Bean FraudRule<TestSettings> testImplementation(RuleDefinition<TestSettings> testDefinition) {
            return new FraudRule<>() {
                public int maximumEvidenceFacts() {return 2;}
                public RuleDefinition<TestSettings> definition() {return testDefinition;}
                public RuleResult evaluate(TransactionEvent event,TestSettings settings,Context context) {
                    return new RuleResult(testDefinition.id(),testDefinition.algorithmVersion(),RuleResult.Status.MATCH,"TEST_MATCH",
                            Map.of("evidenceSchemaVersion",new RuleResult.Number(BigDecimal.ONE),"marker",new RuleResult.Text(settings.marker())));
                }
            };
        }
    }
    private TransactionEvent event(String amount) {
        return new TransactionEvent("event","customer","instrument",TransactionEvent.Type.CARD_PURCHASE,TransactionEvent.Status.BOOKED,
                TransactionEvent.Channel.ECOMMERCE,Money.parse("ZAR",amount),"GROCERIES",Instant.parse("2026-10-15T10:00:00Z"),null);
    }
}
