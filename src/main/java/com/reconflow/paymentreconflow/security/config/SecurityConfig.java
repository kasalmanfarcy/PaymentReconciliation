package com.reconflow.paymentreconflow.security.config;

import com.reconflow.paymentreconflow.security.auth.PasswordChangeRequiredFilter;
import com.reconflow.paymentreconflow.security.tenant.TenantContextFilter;
import com.reconflow.paymentreconflow.security.tenant.TenantLookup;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ProblemDetailWriter;
import com.reconflow.paymentreconflow.shared.error.ProblemDetails;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The security filter chain (authentication.plan §6): a stateless OAuth2 resource server that
 * validates the app's own RS256 JWTs. M10.1 changes only the JwtDecoder (to Keycloak's
 * issuer-uri); nothing else here knows where tokens come from.
 *
 * <p>Public: login, refresh and logout (the last two are authenticated by the refresh cookie, not
 * a bearer token) and health. Everything else needs a valid bearer token; paths are opened one at
 * a time, never {@code permitAll()} "for now".
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
// @PreAuthorize on controller methods: permissions, never role names (authentication.plan §5).
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint problemEntryPoint,
            AccessDeniedHandler problemDeniedHandler,
            ProblemDetailWriter problemWriter,
            TenantLookup tenantLookup) {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens aren't sent automatically by browsers, so CSRF can't use them. The
                // refresh cookie is: SameSite=Strict plus Path=/api/v1/auth confine it to
                // same-site requests to the auth endpoints (authentication.plan §3).
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Container error dispatches render an error for a request already
                        // authorized (or rejected) on its way in.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                        .permitAll()
                        // Granted only by TenantContextFilter in platform mode, from the database.
                        .requestMatchers("/api/v1/platform/**")
                        .hasAuthority(Permission.PLATFORM_ORG_MANAGE.name())
                        .anyRequest()
                        .authenticated())
                // Invalid tokens fail inside BearerTokenAuthenticationFilter, which uses this
                // entry point rather than the one under exceptionHandling; both must be ours.
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(Customizer.withDefaults()).authenticationEntryPoint(problemEntryPoint))
                // 401 and 403 happen in filters, before GlobalExceptionHandler can see them, so
                // they are written here in the same ProblemDetail shape.
                .exceptionHandling(errors ->
                        errors.authenticationEntryPoint(problemEntryPoint).accessDeniedHandler(problemDeniedHandler))
                // Bearer authentication, then the pending-password gate, then tenant resolution,
                // then authorization (which sees the authentication the tenant filter installed).
                .addFilterAfter(new PasswordChangeRequiredFilter(problemWriter), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new TenantContextFilter(tenantLookup, problemWriter), PasswordChangeRequiredFilter.class);
        return http.build();
    }

    /** 401 {@code unauthenticated}; deliberately the same body for a missing, expired or forged token. */
    @Bean
    AuthenticationEntryPoint problemEntryPoint(ProblemDetailWriter problemWriter) {
        return (request, response, ex) -> problemWriter.write(
                request,
                response,
                ProblemDetails.of(
                        HttpStatus.UNAUTHORIZED, ProblemCodes.UNAUTHENTICATED, "Authentication is required."));
    }

    @Bean
    AccessDeniedHandler problemDeniedHandler(ProblemDetailWriter problemWriter) {
        return (request, response, ex) -> problemWriter.write(
                request,
                response,
                ProblemDetails.of(
                        HttpStatus.FORBIDDEN, ProblemCodes.FORBIDDEN, "You do not have permission to do this."));
    }

    /**
     * No username/password authentication through Spring Security: login is our own endpoint.
     * Declaring this stops Boot generating (and logging) a default user and password.
     */
    @Bean
    UserDetailsService noUsers() {
        return username -> {
            throw new UsernameNotFoundException("Spring Security user lookups are not used");
        };
    }
}
