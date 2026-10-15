package za.co.sibusiso.fraud.ingress;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.sibusiso.fraud.configuration.ServiceLimits;

public final class IngressFilter extends OncePerRequestFilter {
    private final CredentialAuthenticator authenticator;
    private final SharedRateLimiter limiter;
    private final ServiceLimits limits;
    private final Semaphore admission;

    public IngressFilter(CredentialAuthenticator authenticator, SharedRateLimiter limiter, ServiceLimits limits) {
        this.authenticator = authenticator;
        this.limiter = limiter;
        this.limits = limits;
        this.admission = new Semaphore(limits.operations().maxConcurrentRequestsPerReplica());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ApiProblems.prepare(request, response);
        IngressRoute route = IngressRoute.resolve(request.getMethod(), request.getRequestURI());
        if (route == null) { chain.doFilter(request, response); return; }
        if (!admission.tryAcquire()) {
            ApiProblems.write(request, response, new ApiFailure(503, "CAPACITY_EXCEEDED", 1));
            return;
        }
        try {
            try {
                var deadline = new RequestDeadline(limits.api().businessDeadlineMillis());
                request.setAttribute(RequestDeadline.ATTRIBUTE, deadline);
                var headers = Collections.list(request.getHeaders("X-API-Key"));
                if (headers.size() != 1) throw new ApiFailure(401, "INVALID_CREDENTIAL");
                Caller caller = authenticator.authenticate(headers.getFirst());
                deadline.check();
                if (!caller.roles().contains(route.role())) throw new ApiFailure(403, "FORBIDDEN");
                limiter.acquire(caller, route);
                deadline.check();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null,
                        List.of(new SimpleGrantedAuthority(route.role().name()))));
                SecurityContextHolder.setContext(context);
            } catch (ApiFailure failure) {
                ApiProblems.write(request, response, failure);
                return;
            } catch (DataAccessException dependencyFailure) {
                ApiProblems.write(request, response, new ApiFailure(503, "DEPENDENCY_UNAVAILABLE", 1));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            admission.release();
            SecurityContextHolder.clearContext();
        }
    }
}
