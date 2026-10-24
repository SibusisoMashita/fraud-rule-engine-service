package za.co.sibusiso.fraud.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import za.co.sibusiso.fraud.evaluation.rules.CumulativeSpendRule;
import za.co.sibusiso.fraud.evaluation.rules.FraudRule;
import za.co.sibusiso.fraud.evaluation.rules.HighAmountRule;
import za.co.sibusiso.fraud.evaluation.rules.ImpossibleTravelRule;
import za.co.sibusiso.fraud.evaluation.rules.RuleEngine;
import za.co.sibusiso.fraud.evaluation.rules.SmallPaymentBurstRule;
import za.co.sibusiso.fraud.evaluation.rules.TransactionVelocityRule;

import java.util.List;

@Configuration
@Profile("!migrate")
public class EvaluationConfiguration {
    @Bean
    HighAmountRule highAmountRule() {
        return new HighAmountRule();
    }

    @Bean
    TransactionVelocityRule transactionVelocityRule() {
        return new TransactionVelocityRule();
    }

    @Bean
    CumulativeSpendRule cumulativeSpendRule() {
        return new CumulativeSpendRule();
    }

    @Bean
    ImpossibleTravelRule impossibleTravelRule(
            za.co.sibusiso.fraud.configuration.ServiceLimits limits) {
        return new ImpossibleTravelRule(limits.api().maxLocationCandidatesPerEvaluation());
    }

    @Bean
    SmallPaymentBurstRule smallPaymentBurstRule() {
        return new SmallPaymentBurstRule();
    }

    @Bean
    RuleEngine ruleEngine(
            List<FraudRule<?>> implementations,
            za.co.sibusiso.fraud.configuration.ServiceLimits limits) {
        return new RuleEngine(implementations, limits.api().maxEvidenceFactsPerRule());
    }
}
