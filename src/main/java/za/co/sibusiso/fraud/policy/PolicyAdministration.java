package za.co.sibusiso.fraud.policy;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.sibusiso.fraud.configuration.PolicyLoader;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.domain.ContractChecks;

@Configuration
@Profile("policy-admin")
public class PolicyAdministration {
    @Bean
    CommandLineRunner administerPolicy(JdbcTemplate jdbc, PolicyLoader loader, StrictYaml yaml, Environment environment,
            ConfigurableApplicationContext context) {
        return args -> {
            String command = environment.getRequiredProperty("fraud.policy-command");
            ContractChecks.require(command.equals("seed") || command.equals("activate"), "Unknown policy command");
            boolean seed = command.equals("seed");
            String existing = jdbc.queryForObject("SELECT policy_hash FROM fraud.policy_head WHERE singleton", String.class);
            if (seed && existing != null) {
                System.out.println("Existing active policy preserved: " + existing);
                context.close();
                return;
            }
            var resource = StrictYaml.localResource(context, environment.getRequiredProperty("fraud.policy-input"));
            String text = yaml.readText(resource);
            var snapshot = loader.read(text);
            String activated = jdbc.queryForObject("SELECT fraud.activate_policy(?, ?, ?)", String.class,
                    snapshot.policyHash(), text, seed);
            System.out.println("Active policy: " + activated);
            context.close();
        };
    }
}
