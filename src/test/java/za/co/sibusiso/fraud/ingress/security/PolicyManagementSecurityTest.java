package za.co.sibusiso.fraud.ingress.security;

import jakarta.servlet.Filter;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.configuration.StrictYaml;
import za.co.sibusiso.fraud.ingress.ApiPaths;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyManagementSecurityTest {
    private static final String SECRET = "a".repeat(64);
    private static final String ADMIN_KEY = "admin." + SECRET;
    private static final String ORDINARY_KEY = "ordinary." + SECRET;
    private static final Caller ADMIN =
            new Caller("policy-operator", "other-scope", Set.of(Caller.Role.POLICY_ADMIN), Set.of(), 1);
    private static final Caller ORDINARY =
            new Caller("producer", "scope-a", Set.of(Caller.Role.INGEST, Caller.Role.READ, Caller.Role.POLICY), Set.of(), 1);
    private static final List<Route> ADMIN_ROUTES = List.of(
            new Route(HttpMethod.GET, ApiPaths.ADMIN_POLICY),
            new Route(HttpMethod.PUT, ApiPaths.ADMIN_POLICY),
            new Route(HttpMethod.POST, ApiPaths.ADMIN_POLICY_VALIDATE));
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(SecurityConfiguration.class, TestConfiguration.class);

    @Test
    void disabledByDefaultDeniesEveryAdminRouteWithoutCredentialOrQuotaWork() {
        runner.run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var route : ADMIN_ROUTES) {
                var response = call(mvc, route, ADMIN_KEY);
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(response.getContentAsString()).contains("FORBIDDEN");
            }
            assertThat(context.getBean(ProbeController.class).calls).hasValue(0);
            verifyNoInteractions(context.getBean(CredentialStore.class));
            verify(context.getBean(StringRedisTemplate.class), never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        });
    }

    @Test
    void enabledRoutesRequireAnExplicitGlobalAdminPermissionWithoutEscalatingOrdinaryKeys() {
        enabled().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var route : ADMIN_ROUTES) {
                var response = call(mvc, route, ORDINARY_KEY);
                assertThat(response.getStatus()).isEqualTo(403);
                assertThat(response.getContentAsString()).contains("FORBIDDEN");
            }
            assertThat(call(mvc, new Route(HttpMethod.GET, ApiPaths.RULES), ORDINARY_KEY).getStatus()).isEqualTo(200);
            assertThat(call(mvc, new Route(HttpMethod.POST, ApiPaths.TRANSACTIONS), ADMIN_KEY).getStatus()).isEqualTo(403);
            assertThat(context.getBean(ProbeController.class).calls).hasValue(0);
        });
    }

    @Test
    void allAdminOperationsShareTheConfiguredPolicyQuotaAndTheSamePrincipalBucket() {
        enabled().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var route : ADMIN_ROUTES) assertThat(call(mvc, route, ADMIN_KEY).getStatus()).isEqualTo(200);
            assertThat(context.getBean(ProbeController.class).calls).hasValue(ADMIN_ROUTES.size());
            var arguments = ArgumentCaptor.forClass(Object[].class);
            verify(context.getBean(StringRedisTemplate.class), times(ADMIN_ROUTES.size())).execute(
                    any(RedisScript.class),
                    eq(List.of(SharedRateLimiter.key(ADMIN, IngressRoute.POLICY_ADMIN))),
                    arguments.capture());
            var bucket = context.getBean(ServiceLimits.class).api().rateLimits().policy();
            assertThat(arguments.getAllValues()).allSatisfy(values -> assertThat(values).containsExactly(
                    Integer.toString(bucket.capacity()), Integer.toString(bucket.refillPerSecond())));
        });
    }

    @Test
    void missingIncorrectAndRevokedCredentialsAreRejectedBeforeAnyAdminWork() {
        enabled().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            var route = new Route(HttpMethod.PUT, ApiPaths.ADMIN_POLICY);
            assertCredentialFailure(call(mvc, route, null));
            assertCredentialFailure(call(mvc, route, "admin." + "b".repeat(64)));
            when(context.getBean(CredentialStore.class).active("admin")).thenReturn(Optional.empty());
            assertCredentialFailure(call(mvc, route, ADMIN_KEY));
            assertThat(context.getBean(ProbeController.class).calls).hasValue(0);
            verify(context.getBean(StringRedisTemplate.class), never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        });
    }

    @Test
    void exhaustedAdminQuotaRejectsEveryAdminOperationWithRetryAfter() {
        enabled().run(context -> {
            when(context.getBean(StringRedisTemplate.class).execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenReturn(List.of(0L, 1000L));
            var mvc = mvc(context.getSourceApplicationContext());
            for (var route : ADMIN_ROUTES) {
                var response = call(mvc, route, ADMIN_KEY);
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                assertThat(response.getContentAsString()).contains("RATE_LIMITED");
            }
            assertThat(context.getBean(ProbeController.class).calls).hasValue(0);
        });
    }

    @Test
    void redisOutageClosesAdminAdmissionWithoutExposingDependencyDetails() {
        enabled().run(context -> {
            when(context.getBean(StringRedisTemplate.class).execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenThrow(new DataAccessResourceFailureException("private Redis diagnostics"));
            var mvc = mvc(context.getSourceApplicationContext());
            var response = call(mvc, new Route(HttpMethod.PUT, ApiPaths.ADMIN_POLICY), ADMIN_KEY);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("LIMITER_UNAVAILABLE", "NOT_ACCEPTED")
                    .doesNotContain("private Redis diagnostics", SECRET);
            assertThat(context.getBean(ProbeController.class).calls).hasValue(0);
        });
    }

    @Test
    void unrecognizedAdminMethodsAndPathsRemainClosedWithoutQuotaWork() {
        enabled().run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var route : List.of(
                    new Route(HttpMethod.DELETE, ApiPaths.ADMIN_POLICY),
                    new Route(HttpMethod.POST, ApiPaths.ADMIN_POLICY),
                    new Route(HttpMethod.GET, ApiPaths.ADMIN_POLICY_VALIDATE),
                    new Route(HttpMethod.POST, ApiPaths.ADMIN_POLICY + "/activate"))) {
                assertThat(call(mvc, route, ADMIN_KEY).getStatus()).isEqualTo(403);
            }
            assertThat(context.getBean(ProbeController.class).calls).hasValue(0);
            verifyNoInteractions(context.getBean(CredentialStore.class));
            verify(context.getBean(StringRedisTemplate.class), never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        });
    }

    private WebApplicationContextRunner enabled() {
        return runner.withPropertyValues("fraud.policy-management.enabled=true");
    }

    private static MockMvc mvc(org.springframework.web.context.WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    private static org.springframework.mock.web.MockHttpServletResponse call(MockMvc mvc, Route route, String key) throws Exception {
        var request = MockMvcRequestBuilders.request(route.method(), route.path()).contentType(MediaType.APPLICATION_JSON);
        if (key != null) request.header("X-API-Key", key);
        if (route.method() == HttpMethod.PUT || route.method() == HttpMethod.POST) request.content("{}");
        return mvc.perform(request).andReturn().getResponse();
    }

    private static void assertCredentialFailure(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("ApiKey");
        assertThat(response.getContentAsString()).contains("INVALID_CREDENTIAL").doesNotContain(SECRET);
    }

    private record Route(HttpMethod method, String path) { }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfiguration {
        @Bean ServiceLimits limits() { return new StrictYaml().read(new ClassPathResource("config/service.example.yaml"), ServiceLimits.class); }
        @Bean CredentialStore credentialStore() {
            var store = mock(CredentialStore.class);
            var hash = HexFormat.of().formatHex(CredentialAuthenticator.digest(SECRET));
            when(store.active("admin")).thenReturn(Optional.of(new CredentialStore.Credential(hash, ADMIN)));
            when(store.active("ordinary")).thenReturn(Optional.of(new CredentialStore.Credential(hash, ORDINARY)));
            return store;
        }
        @Bean CredentialAuthenticator authenticator(CredentialStore store) { return new CredentialAuthenticator(store); }
        @Bean @SuppressWarnings("unchecked") StringRedisTemplate redis() {
            var redis = mock(StringRedisTemplate.class);
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L));
            return redis;
        }
        @Bean SharedRateLimiter limiter(StringRedisTemplate redis, ServiceLimits limits) { return new SharedRateLimiter(redis, limits); }
        @Bean ProbeController controller() { return new ProbeController(); }
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class ProbeController {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping(ApiPaths.ADMIN_POLICY) String current() { calls.incrementAndGet(); return "current"; }
        @PutMapping(ApiPaths.ADMIN_POLICY) String activate() { calls.incrementAndGet(); return "activated"; }
        @PostMapping(ApiPaths.ADMIN_POLICY_VALIDATE) String validate() { calls.incrementAndGet(); return "validated"; }
        @GetMapping(ApiPaths.RULES) String rules() { return "rules"; }
    }
}
