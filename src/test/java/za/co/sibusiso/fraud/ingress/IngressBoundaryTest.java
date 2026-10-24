package za.co.sibusiso.fraud.ingress;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import za.co.sibusiso.fraud.configuration.*;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.request.RequestDeadline;
import za.co.sibusiso.fraud.ingress.security.Caller;
import za.co.sibusiso.fraud.ingress.security.CredentialAuthenticator;
import za.co.sibusiso.fraud.ingress.security.CredentialStore;
import za.co.sibusiso.fraud.ingress.security.IngressFilter;
import za.co.sibusiso.fraud.ingress.security.SharedRateLimiter;

import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

class IngressBoundaryTest {
    private static final String SECRET = "a".repeat(64);
    private static final Caller CALLER =
            new Caller("producer", "scope-a", Set.of(Caller.Role.INGEST), Set.of(), 1);

    @Test
    void authenticatorBoundsKeysAndVerifiesSecretWithoutCredentialMaterialInPrincipal() {
        CredentialStore store = mock(CredentialStore.class);
        when(store.active("key"))
                .thenReturn(
                        Optional.of(
                                new CredentialStore.Credential(
                                        HexFormat.of()
                                                .formatHex(CredentialAuthenticator.digest(SECRET)),
                                        CALLER)));
        var authenticator = new CredentialAuthenticator(store);
        for (String malformed :
                new String[] {
                    "",
                    "key",
                    "key." + "a".repeat(65),
                    "x".repeat(65) + "." + SECRET,
                    "key." + "A".repeat(64)
                }) {
            assertThatThrownBy(() -> authenticator.authenticate(malformed))
                    .isInstanceOf(ApiFailure.class);
        }
        verifyNoInteractions(store);
        assertThat(authenticator.authenticate("key." + SECRET)).isSameAs(CALLER);
        assertThatThrownBy(() -> authenticator.authenticate("key." + "b".repeat(64)))
                .isInstanceOf(ApiFailure.class);
        assertThat(CALLER.toString()).doesNotContain(SECRET);
    }

    @Test
    void admissionRejectsExcessWorkAndReleasesPermitsAndSecurityContextOnEveryExit()
            throws Exception {
        var yaml = new StrictYaml();
        String text =
                yaml.readText(new ClassPathResource("config/service.example.yaml"))
                        .replace(
                                "maxConcurrentRequestsPerReplica: 20",
                                "maxConcurrentRequestsPerReplica: 1")
                        .replace("httpMinSpareThreads: 2", "httpMinSpareThreads: 1");
        var auth = mock(CredentialAuthenticator.class);
        when(auth.authenticate("key." + SECRET)).thenReturn(CALLER);
        var limiter = mock(SharedRateLimiter.class);
        var filter = new IngressFilter(auth, limiter, yaml.read(text, ServiceLimits.class));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running =
                    executor.submit(
                            () -> {
                                try {
                                    filter.doFilter(
                                            request(),
                                            new MockHttpServletResponse(),
                                            (request, response) -> {
                                                assertThat(
                                                                SecurityContextHolder.getContext()
                                                                        .getAuthentication()
                                                                        .getPrincipal())
                                                        .isSameAs(CALLER);
                                                assertThat(
                                                                request.getAttribute(
                                                                        RequestDeadline.ATTRIBUTE))
                                                        .isInstanceOf(RequestDeadline.class);
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "Release timed out");
                                                } catch (InterruptedException failure) {
                                                    Thread.currentThread().interrupt();
                                                    throw new AssertionError(failure);
                                                }
                                            });
                                    assertThat(
                                                    SecurityContextHolder.getContext()
                                                            .getAuthentication())
                                            .isNull();
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var excess = new MockHttpServletResponse();
                filter.doFilter(
                        request(),
                        excess,
                        (request, response) -> {
                            throw new AssertionError("Capacity bypass");
                        });
                assertThat(excess.getStatus()).isEqualTo(503);
                assertThat(excess.getContentAsString()).contains("CAPACITY_EXCEEDED");
            } finally {
                release.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
        }
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request(),
                                        new MockHttpServletResponse(),
                                        (request, response) -> {
                                            throw new org.springframework.dao
                                                    .DataAccessResourceFailureException(
                                                    "Downstream outcome is not an admission decision");
                                        }))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        var recovered = new MockHttpServletResponse();
        filter.doFilter(
                request(),
                recovered,
                (request, response) -> response.getWriter().write("admitted"));
        assertThat(recovered.getContentAsString()).isEqualTo("admitted");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void deadlineCannotBeSilentlyExceededBeforeDispatch() throws Exception {
        var deadline = new RequestDeadline(1);
        Thread.sleep(10);
        assertThatThrownBy(deadline::check)
                .isInstanceOfSatisfying(
                        ApiFailure.class, e -> assertThat(e.code()).isEqualTo("DEADLINE_EXCEEDED"));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/transactions");
        request.addHeader("X-API-Key", "key." + SECRET);
        return request;
    }
}
