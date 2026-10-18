package za.co.sibusiso.fraud.ingress.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ApiProblems;
import za.co.sibusiso.fraud.ingress.request.RequestDeadline;

public final class IngressFilter extends OncePerRequestFilter {
    private static final String API_KEY_HEADER = "X-API-Key";
    private final CredentialAuthenticator authenticator;
    private final SharedRateLimiter limiter;
    private final ServiceLimits limits;
    private final Semaphore admission;
    private final boolean policyManagementEnabled;

    public IngressFilter(
            CredentialAuthenticator authenticator,
            SharedRateLimiter limiter,
            ServiceLimits limits) {
        this(authenticator, limiter, limits, false);
    }

    public IngressFilter(
            CredentialAuthenticator authenticator,
            SharedRateLimiter limiter,
            ServiceLimits limits,
            boolean policyManagementEnabled) {
        this.authenticator = authenticator;
        this.limiter = limiter;
        this.limits = limits;
        this.policyManagementEnabled = policyManagementEnabled;
        this.admission = new Semaphore(limits.operations().maxConcurrentRequestsPerReplica());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ApiProblems.prepare(request, response);
        IngressRoute route =
                IngressRoute.resolve(
                        request.getMethod(), request.getRequestURI(), policyManagementEnabled);
        if (route == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!admission.tryAcquire()) {
            ApiProblems.write(
                    request,
                    response,
                    new ApiFailure(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            ErrorCode.CAPACITY_EXCEEDED,
                            ApiFailure.DEFAULT_RETRY_AFTER_SECONDS));
            return;
        }
        try {
            try {
                var deadline = new RequestDeadline(limits.api().businessDeadlineMillis());
                request.setAttribute(RequestDeadline.ATTRIBUTE, deadline);
                var headers = Collections.list(request.getHeaders(API_KEY_HEADER));
                if (headers.size() != 1)
                    throw new ApiFailure(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIAL);
                Caller caller = authenticator.authenticate(headers.getFirst());
                deadline.check();
                if (!caller.roles().contains(route.role()))
                    throw new ApiFailure(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
                limiter.acquire(caller, route);
                deadline.check();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                caller,
                                null,
                                List.of(new SimpleGrantedAuthority(route.role().name()))));
                SecurityContextHolder.setContext(context);
            } catch (ApiFailure failure) {
                ApiProblems.write(request, response, failure);
                return;
            } catch (DataAccessException dependencyFailure) {
                ApiProblems.write(
                        request,
                        response,
                        new ApiFailure(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                ErrorCode.DEPENDENCY_UNAVAILABLE,
                                ApiFailure.DEFAULT_RETRY_AFTER_SECONDS,
                                dependencyFailure));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            admission.release();
            SecurityContextHolder.clearContext();
        }
    }
}
