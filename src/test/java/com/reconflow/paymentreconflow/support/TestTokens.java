package com.reconflow.paymentreconflow.support;

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

/**
 * Access tokens as Keycloak would issue them (M10.1), signed by {@link TestIssuer}, so endpoint
 * tests don't need a running identity provider, and can build tokens Keycloak would never issue:
 * expired, for another audience, from another issuer, or signed by an unknown key.
 *
 * <p>The subject is the user's {@code external_subject}. {@link TestData} links every fixture user
 * to its own id ({@code external_subject = id}), so {@code forUser(user.id())} works unchanged.
 */
public final class TestTokens {

    private final TestIssuer issuer;
    private final String audience;

    public TestTokens(TestIssuer issuer, String audience) {
        this.issuer = issuer;
        this.audience = audience;
    }

    /** A valid 15-minute token whose subject is the user's id (TestData links them). */
    public String forUser(UUID userId) {
        return forSubject(userId.toString(), claims -> {});
    }

    /** A token for the user with the claims adjusted, e.g. an expiry in the past or another aud. */
    public String forUser(UUID userId, Consumer<JwtClaimsSet.Builder> customizer) {
        return forSubject(userId.toString(), customizer);
    }

    /** A token for an arbitrary IdP subject (unknown here, or to be linked by verified email). */
    public String forSubject(String subject, Consumer<JwtClaimsSet.Builder> customizer) {
        return encode(issuer.encoder(), claims(subject, customizer));
    }

    /** Correct claims, signed by a key the issuer never published. */
    public String signedWithAnotherKey(UUID userId) {
        return encode(TestIssuer.foreignEncoder(), claims(userId.toString(), c -> {}));
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private JwtClaimsSet claims(String subject, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject(subject)
                .issuer(issuer.issuer())
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString())
                .claim("azp", "reconflow-web");
        customizer.accept(builder);
        return builder.build();
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
