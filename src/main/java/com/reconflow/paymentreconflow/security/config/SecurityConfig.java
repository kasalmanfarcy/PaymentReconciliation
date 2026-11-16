package com.reconflow.paymentreconflow.security.config;

import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ProblemDetailWriter;
import com.reconflow.paymentreconflow.shared.error.ProblemDetails;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY (M2.2): deny-by-default until M3.3 replaces this chain with JWT authentication
 * (plans/authentication.plan §6).
 *
 * <p>Adding Spring Security without this would lock everything, including
 * {@code /actuator/health}, behind HTTP Basic with a generated password, so Docker, load
 * balancer and CI health checks would get 401. Here health and info are public and every
 * other path is 401: with no login mechanism yet, nothing else can be authenticated. That is
 * the safe default; paths are opened one at a time, never {@code permitAll()} "for now".
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailWriter problemWriter) {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // A stateless bearer-token API with no cookies yet; M3.3 revisits CSRF for the
                // refresh-token cookie (SameSite + Path).
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
                        .anyRequest()
                        .authenticated())
                // 401 and 403 happen in filters, before GlobalExceptionHandler can see them, so
                // they are written here in the same ProblemDetail shape.
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                (request, response, ex) -> problemWriter.write(
                                        request,
                                        response,
                                        ProblemDetails.of(
                                                HttpStatus.UNAUTHORIZED,
                                                ProblemCodes.UNAUTHENTICATED,
                                                "Authentication is required.")))
                        .accessDeniedHandler((request, response, ex) -> problemWriter.write(
                                request,
                                response,
                                ProblemDetails.of(
                                        HttpStatus.FORBIDDEN,
                                        ProblemCodes.FORBIDDEN,
                                        "You do not have permission to do this."))));
        return http.build();
    }

    /** No users until M3.3. Declaring this stops Boot generating (and logging) a default password. */
    @Bean
    UserDetailsService noUsers() {
        return username -> {
            throw new UsernameNotFoundException("No users are configured yet");
        };
    }
}
