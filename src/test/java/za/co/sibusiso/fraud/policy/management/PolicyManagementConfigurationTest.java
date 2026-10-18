package za.co.sibusiso.fraud.policy.management;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;

import static org.assertj.core.api.Assertions.*;

class PolicyManagementConfigurationTest {
    @Test void disabledFeatureNeedsNeitherWriterConfigurationNorDatabaseBeans() {
        new ApplicationContextRunner().withUserConfiguration(PolicyManagementConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JdbcPolicyWriter.class).doesNotHaveBean(PolicyManagementService.class);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"migrate", "policy-admin"})
    void inheritedFeatureFlagCannotCreateAnHttpWriterInCliProfiles(String profile) {
        new ApplicationContextRunner().withPropertyValues("fraud.policy-management.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withUserConfiguration(PolicyManagementConfiguration.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JdbcPolicyWriter.class);
                });
    }

    @Test void enabledFeatureRejectsMissingSecretsAndUnboundedPoolSettingsBeforeConnecting() {
        var limits = new StrictYaml().read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class);
        var configuration = new PolicyManagementConfiguration();
        for (int connections : new int[] {0, -1, limits.operations().dbPoolMaxConnectionsPerReplica() + 1}) {
            var environment = new MockEnvironment().withProperty("fraud.policy-management.max-connections", "" + connections);
            assertThatThrownBy(() -> configuration.policyWriter(environment, limits)).isInstanceOf(IllegalArgumentException.class);
        }
        var environment = new MockEnvironment().withProperty("fraud.policy-management.max-connections", "1");
        assertThatThrownBy(() -> configuration.policyWriter(environment, limits)).hasMessageContaining("password");
        environment.withProperty("fraud.policy-management.password", " ");
        assertThatThrownBy(() -> configuration.policyWriter(environment, limits)).hasMessageContaining("password");
    }
}
