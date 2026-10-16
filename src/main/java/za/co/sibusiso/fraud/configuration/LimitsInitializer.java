package za.co.sibusiso.fraud.configuration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;
import za.co.sibusiso.fraud.domain.ContractChecks;

/** Loads operational settings before pools/listeners are constructed. */
public final class LimitsInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        var environment = context.getEnvironment();
        if (environment.acceptsProfiles(Profiles.of("migrate"))) return;
        String location = environment.getRequiredProperty("fraud.limits-location");
        ServiceLimits limits = new StrictYaml().read(StrictYaml.localResource(context, location), ServiceLimits.class);
        String deployedGrace = environment.getProperty("fraud.deployment-stop-grace-seconds");
        if (deployedGrace != null) {
            ContractChecks.require(Integer.parseInt(deployedGrace)
                    == limits.operations().containerStopGraceSeconds(), "Declared container stop grace differs from deployment");
        }
        var properties = new java.util.LinkedHashMap<String, Object>(limits.runtimeProperties());
        if (environment.acceptsProfiles(Profiles.of("policy-admin"))) {
            // One-shot administration establishes its first connection before borrowing it.
            properties.put("spring.datasource.hikari.initialization-fail-timeout", limits.operations().poolAcquireTimeoutMillis());
        }
        environment.getPropertySources().addFirst(new MapPropertySource("validated-service-limits", properties));
        context.getBeanFactory().registerSingleton("serviceLimits", limits);
    }
}
