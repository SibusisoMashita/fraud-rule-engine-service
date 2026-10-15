package za.co.sibusiso.fraud.evaluation;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!migrate")
public class EvaluationConfiguration {
    @Bean HighAmountRule highAmountRule() { return new HighAmountRule(); }
    @Bean TransactionVelocityRule transactionVelocityRule() { return new TransactionVelocityRule(); }
    @Bean CumulativeSpendRule cumulativeSpendRule() { return new CumulativeSpendRule(); }
    @Bean ImpossibleTravelRule impossibleTravelRule(za.co.sibusiso.fraud.configuration.ServiceLimits limits) {
        return new ImpossibleTravelRule(limits.api().maxLocationCandidatesPerEvaluation());
    }
    @Bean RuleEngine ruleEngine(List<FraudRule<?>> implementations, za.co.sibusiso.fraud.configuration.ServiceLimits limits) { return new RuleEngine(implementations, limits.api().maxEvidenceFactsPerRule()); }
}
