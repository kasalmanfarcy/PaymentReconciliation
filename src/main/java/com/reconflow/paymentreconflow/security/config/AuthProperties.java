package com.reconflow.paymentreconflow.security.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.auth.*} (M10.1). Token validation is Spring's resource server, configured under
 * {@code spring.security.oauth2.resourceserver.jwt} (issuer and audience); what remains here is how
 * ReconFlow talks to Keycloak's Admin REST API to create and manage users.
 *
 * @param keycloak the Admin REST API, through a confidential service-account client that has only
 *     the {@code manage-users} role
 */
@Validated
@ConfigurationProperties("reconflow.auth")
public record AuthProperties(@DefaultValue Keycloak keycloak) {

    /**
     * @param baseUrl Keycloak's base URL as the application reaches it, e.g. {@code http://keycloak:8180}
     * @param realm the realm whose users ReconFlow manages
     * @param adminClientId the service-account client
     * @param adminClientSecret from the environment only ({@code KEYCLOAK_ADMIN_CLIENT_SECRET}); never
     *     committed. Without it, user management fails (and {@code prod} refuses to start).
     * @param timeout connect and read timeout for every Admin API call: a slow IdP must not hold a
     *     request thread (and its database transaction) for long
     */
    public record Keycloak(
            @DefaultValue("http://localhost:8180") @NotBlank String baseUrl,
            @DefaultValue("reconflow") @NotBlank String realm,
            @DefaultValue("reconflow-admin") @NotBlank String adminClientId,
            @Nullable String adminClientSecret,
            @DefaultValue("5s") Duration timeout) {}
}
