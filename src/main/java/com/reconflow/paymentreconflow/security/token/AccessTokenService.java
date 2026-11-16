package com.reconflow.paymentreconflow.security.token;

import com.nimbusds.jose.jwk.RSAKey;
import com.reconflow.paymentreconflow.security.config.AuthProperties;
import com.reconflow.paymentreconflow.security.user.AppUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the 15-minute access JWT (authentication.plan §3).
 *
 * <p>Identity only: {@code sub}, {@code iss}, {@code aud}, {@code iat}, {@code exp}, {@code jti},
 * and {@code pwd_change} while a temporary password is pending. No roles, no organization, no
 * platform-admin flag: membership and permissions are looked up on every request, so removing
 * access takes effect on the next request instead of when the token expires.
 */
@Service
public class AccessTokenService {

    public static final String PASSWORD_CHANGE_CLAIM = "pwd_change";

    /** The token and its lifetime in seconds, as returned to the client. */
    public record AccessToken(String value, long expiresIn) {}

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final String keyId;
    private final Clock clock;

    AccessTokenService(JwtEncoder encoder, AuthProperties properties, RSAKey jwtSigningKey, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.keyId = jwtSigningKey.getKeyID();
        this.clock = clock;
    }

    public AccessToken issue(AppUser user) {
        Instant now = clock.instant();
        var ttl = properties.jwt().accessTokenTtl();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                // Reserved for a future denylist; unused in v1.
                .id(UUID.randomUUID().toString());
        if (user.isMustChangePassword()) {
            claims.claim(PASSWORD_CHANGE_CLAIM, true);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keyId)
                .type("JWT")
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
        return new AccessToken(value, ttl.toSeconds());
    }
}
