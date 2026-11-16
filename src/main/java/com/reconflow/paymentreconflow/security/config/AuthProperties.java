package com.reconflow.paymentreconflow.security.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.auth.*}: everything about tokens that must not be a string literal in code
 * (authentication.plan §3, §7). {@code iss}/{@code aud} are configuration because M10.1 changes
 * them to Keycloak's.
 */
@Validated
@ConfigurationProperties("reconflow.auth")
public record AuthProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Refresh refresh,
        @DefaultValue Cookie cookie,
        @DefaultValue RateLimit rateLimit) {

    /**
     * @param privateKey PKCS#8 PEM of the RS256 signing key (env {@code JWT_PRIVATE_KEY}); never
     *     committed
     * @param publicKey X.509 PEM of the matching public key (env {@code JWT_PUBLIC_KEY})
     * @param generateEphemeralKey local and test only: generate a key pair at startup instead.
     *     Tokens then die on restart. Any other profile fails startup without a configured key.
     */
    public record Jwt(
            @DefaultValue("reconflow") @NotBlank String issuer,
            @DefaultValue("reconflow-api") @NotBlank String audience,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("60s") Duration clockSkew,
            @Nullable String privateKey,
            @Nullable String publicKey,
            @DefaultValue("false") boolean generateEphemeralKey) {}

    /** Each refresh token lives 7 days; its family (one login's chain) is capped at 30. */
    public record Refresh(
            @DefaultValue("7d") Duration tokenTtl,
            @DefaultValue("30d") Duration familyTtl) {}

    /**
     * {@code secure} defaults to true and only the local profile turns it off (for Safari on
     * http://localhost), so a forgotten setting in production fails safe.
     */
    public record Cookie(@DefaultValue("true") boolean secure) {}

    /** Login attempts per client IP: a token bucket of {@code capacity}, refilled per minute. */
    public record RateLimit(
            @DefaultValue("10") @Min(1) int capacity,
            @DefaultValue("10") @Min(1) int refillPerMinute) {}
}
