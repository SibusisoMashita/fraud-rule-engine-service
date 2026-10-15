package za.co.sibusiso.fraud.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import za.co.sibusiso.fraud.configuration.PolicyLoader;

@Configuration
@Profile("!migrate & !policy-admin")
public class PolicyRuntimeConfiguration {
    @Bean(initMethod = "verifyActivePolicy")
    ActivePolicyProvider activePolicyProvider(ActivePolicyStore store, PolicyLoader loader) {
        return new ActivePolicyProvider(store, loader);
    }
}
