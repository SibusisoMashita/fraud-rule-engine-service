package za.co.sibusiso.fraud.policy;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.policy.definition.BuiltInRules;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;
import za.co.sibusiso.fraud.policy.definition.RuleDefinition;
import za.co.sibusiso.fraud.policy.definition.RuleRegistry;

@Configuration
@Profile("!migrate")
public class PolicyConfiguration {
    @Bean
    StrictYaml strictYaml() { return new StrictYaml(); }

    @Bean
    RuleDefinition<?> highAmount() { return BuiltInRules.highAmount(); }
    @Bean
    RuleDefinition<?> velocity() { return BuiltInRules.velocity(); }
    @Bean
    RuleDefinition<?> cumulativeSpend() { return BuiltInRules.cumulativeSpend(); }
    @Bean
    RuleDefinition<?> impossibleTravel() { return BuiltInRules.impossibleTravel(); }
    @Bean
    RuleDefinition<?> smallPaymentBurst() { return BuiltInRules.smallPaymentBurst(); }

    @Bean
    RuleRegistry ruleRegistry(List<RuleDefinition<?>> definitions, ServiceLimits limits) {
        return RuleRegistry.of(definitions, limits.api().maxRegisteredRules());
    }

    @Bean
    PolicyLoader policyLoader(StrictYaml yaml, RuleRegistry registry) {
        return new PolicyLoader(yaml, registry);
    }
}
