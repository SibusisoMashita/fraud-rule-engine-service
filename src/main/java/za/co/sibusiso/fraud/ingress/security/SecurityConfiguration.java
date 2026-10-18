package za.co.sibusiso.fraud.ingress.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.contracts.ErrorCode;
import za.co.sibusiso.fraud.ingress.ApiPaths;
import za.co.sibusiso.fraud.ingress.error.ApiFailure;
import za.co.sibusiso.fraud.ingress.error.ApiProblems;

@Configuration
@Profile("!migrate & !policy-admin")
class SecurityConfiguration {
    @Bean
    UserDetailsService noLoginUsers() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    WebSecurityCustomizer firewallErrors() {
        return web ->
                web.requestRejectedHandler(
                        (request, response, failure) ->
                                ApiProblems.write(
                                        request,
                                        response,
                                        new ApiFailure(
                                                HttpStatus.BAD_REQUEST,
                                                ErrorCode.INVALID_REQUEST)));
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CredentialAuthenticator authenticator,
            SharedRateLimiter limiter,
            ServiceLimits limits,
            @Value("${fraud.policy-management.enabled:false}") boolean policyManagementEnabled)
            throws Exception {
        return http
                // Credentials use headers; no cookie-based login or browser session exists.
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new IngressFilter(authenticator, limiter, limits, policyManagementEnabled),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(
                        auth -> {
                            auth.requestMatchers(HttpMethod.GET, ApiPaths.LIVE, ApiPaths.READY)
                                    .permitAll();
                            if (limits.api().documentationEnabled())
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/swagger-ui",
                                                "/swagger-ui/",
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/openapi.yaml")
                                        .permitAll();
                            auth.requestMatchers(HttpMethod.POST, ApiPaths.TRANSACTIONS)
                                    .hasAuthority(Caller.Role.INGEST.name())
                                    .requestMatchers(
                                            HttpMethod.GET,
                                            ApiPaths.TRANSACTIONS + "/*",
                                            ApiPaths.EVALUATIONS)
                                    .hasAuthority(Caller.Role.READ.name())
                                    .requestMatchers(HttpMethod.GET, ApiPaths.RULES)
                                    .hasAuthority(Caller.Role.POLICY.name());
                            if (policyManagementEnabled) {
                                auth.requestMatchers(HttpMethod.GET, ApiPaths.ADMIN_POLICY)
                                        .hasAuthority(Caller.Role.POLICY_ADMIN.name())
                                        .requestMatchers(HttpMethod.PUT, ApiPaths.ADMIN_POLICY)
                                        .hasAuthority(Caller.Role.POLICY_ADMIN.name())
                                        .requestMatchers(HttpMethod.POST, ApiPaths.ADMIN_POLICY_VALIDATE)
                                        .hasAuthority(Caller.Role.POLICY_ADMIN.name());
                            }
                            auth.anyRequest().denyAll();
                        })
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, failure) ->
                                                        ApiProblems.write(
                                                                request,
                                                                response,
                                                                new ApiFailure(
                                                                        HttpStatus.FORBIDDEN,
                                                                        ErrorCode.FORBIDDEN)))
                                        .accessDeniedHandler(
                                                (request, response, failure) ->
                                                        ApiProblems.write(
                                                                request,
                                                                response,
                                                                new ApiFailure(
                                                                        HttpStatus.FORBIDDEN,
                                                                        ErrorCode.FORBIDDEN))))
                .build();
    }
}
