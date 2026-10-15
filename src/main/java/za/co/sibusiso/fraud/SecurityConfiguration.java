package za.co.sibusiso.fraud;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import za.co.sibusiso.fraud.configuration.ServiceLimits;
import za.co.sibusiso.fraud.ingress.*;

@Configuration
@Profile("!migrate & !policy-admin")
class SecurityConfiguration {
    @Bean UserDetailsService noLoginUsers() { return new InMemoryUserDetailsManager(); }

    @Bean WebSecurityCustomizer firewallErrors() {
        return web -> web.requestRejectedHandler((request, response, failure) ->
                ApiProblems.write(request, response, new ApiFailure(400, "INVALID_REQUEST")));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CredentialAuthenticator authenticator,
            SharedRateLimiter limiter, ServiceLimits limits) throws Exception {
        return http
                // Credentials use headers; no cookie-based login or browser session exists.
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new IngressFilter(authenticator, limiter, limits), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health/live", "/health/ready").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions").hasAuthority("INGEST")
                        .requestMatchers(HttpMethod.GET, "/api/v1/transactions/*", "/api/v1/evaluations").hasAuthority("READ")
                        .requestMatchers(HttpMethod.GET, "/api/v1/rules").hasAuthority("POLICY")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> ApiProblems.write(request, response, new ApiFailure(403, "FORBIDDEN")))
                        .accessDeniedHandler((request, response, failure) -> ApiProblems.write(request, response, new ApiFailure(403, "FORBIDDEN"))))
                .build();
    }
}
