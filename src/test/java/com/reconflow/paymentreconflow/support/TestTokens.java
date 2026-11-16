package com.reconflow.paymentreconflow.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Mints access tokens with the application's own JwtEncoder, so endpoint tests don't need to log
 * in (bcrypt) for every request, and can build tokens login would never issue: expired, for another
 * audience, or signed by a key the app doesn't trust.
 */
public final class TestTokens {

    private final JwtEncoder appEncoder;
    private final String issuer;
    private final String audience;

    public TestTokens(JwtEncoder appEncoder, String issuer, String audience) {
        this.appEncoder = appEncoder;
        this.issuer = issuer;
        this.audience = audience;
    }

    /** A valid 15-minute token for the user, as login would issue it. */
    public String forUser(UUID userId) {
        return forUser(userId, claims -> {});
    }

    /** A token for the user with the claims adjusted, e.g. an expiry in the past. */
    public String forUser(UUID userId, Consumer<JwtClaimsSet.Builder> customizer) {
        return encode(appEncoder, claims(userId, customizer));
    }

    /** Correct claims, signed by a freshly generated key the application has never seen. */
    public String signedWithAnotherKey(UUID userId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair other = generator.generateKeyPair();
            JwtEncoder foreign = NimbusJwtEncoder.withKeyPair(
                            (RSAPublicKey) other.getPublic(), (RSAPrivateKey) other.getPrivate())
                    .build();
            return encode(foreign, claims(userId, c -> {}));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private JwtClaimsSet claims(UUID userId, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuer(issuer)
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString());
        customizer.accept(builder);
        return builder.build();
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
