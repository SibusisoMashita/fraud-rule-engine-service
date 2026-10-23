package za.co.sibusiso.fraud.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import za.co.sibusiso.fraud.policy.definition.PolicyLoader;

@Configuration
@Profile("!migrate & !policy-admin")
public class PolicyRuntimeConfiguration {
    @Bean(initMethod = "verifyActivePolicy")
    ActivePolicyProvider activePolicyProvider(
            ActivePolicyStore store,
            PolicyLoader loader,
            za.co.sibusiso.fraud.evaluation.rules.RuleEngine engine) {
        return new ActivePolicyProvider(store, loader, engine::verify);
    }
}
