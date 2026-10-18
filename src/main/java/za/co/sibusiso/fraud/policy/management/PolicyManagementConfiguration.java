package za.co.sibusiso.fraud.policy.management;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.domain.ContractChecks;
import za.co.sibusiso.fraud.evaluation.rules.RuleEngine;
import za.co.sibusiso.fraud.policy.definition.PolicyLoader;

@Configuration(proxyBeanMethods = false)
@Profile("!migrate & !policy-admin")
@ConditionalOnProperty(name = "fraud.policy-management.enabled", havingValue = "true")
public class PolicyManagementConfiguration {
    @Bean(destroyMethod = "close")
    JdbcPolicyWriter policyWriter(Environment environment, ServiceLimits limits) {
        int connections = environment.getRequiredProperty("fraud.policy-management.max-connections", Integer.class);
        ContractChecks.require(connections > 0
                        && connections <= limits.operations().dbPoolMaxConnectionsPerReplica(),
                "Policy writer pool must be positive and no larger than the runtime pool");
        String password = environment.getRequiredProperty("fraud.policy-management.password");
        ContractChecks.require(!password.isBlank(), "Policy writer password is required");
        var configuration = new HikariConfig();
        // Use the same validated timeout properties as Boot's runtime pool.
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(configuration));
        // Each activation is one atomic SQL call, independent of runtime transaction settings.
        configuration.setAutoCommit(true);
        configuration.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        configuration.setUsername("fraud_policy_api");
        configuration.setPassword(password);
        configuration.setMaximumPoolSize(connections);
        configuration.setPoolName("policy-writer");
        return new JdbcPolicyWriter(new HikariDataSource(configuration));
    }

    @Bean
    PolicyManagementService policyManagementService(JdbcTemplate runtime, JdbcPolicyWriter writer,
            StrictYaml yaml, PolicyLoader loader, RuleEngine engine) {
        return new PolicyManagementService(runtime, writer, yaml, loader, engine);
    }
}
