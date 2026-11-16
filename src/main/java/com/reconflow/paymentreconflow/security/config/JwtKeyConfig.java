package com.reconflow.paymentreconflow.security.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * The RS256 key pair, and the encoder and decoder built on it (authentication.plan §7).
 *
 * <p>RS256 rather than HS256: only the issuer holds the private key; anything that verifies tokens
 * needs only the public one. That's what lets M10.1 hand issuing to Keycloak while this app keeps
 * verifying. Nimbus does all signing and parsing; nothing here touches JWT bytes.
 *
 * <p>The private key comes from the environment, never the repository. Local and test runs set
 * {@code generate-ephemeral-key} and get a fresh pair at startup; every other profile fails fast
 * without a key, instead of booting and failing on the first login.
 */
@Configuration(proxyBeanMethods = false)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    /** The signing key with its {@code kid}; key rotation will hold several of these. */
    @Bean
    RSAKey jwtSigningKey(AuthProperties properties) {
        KeyPair pair = loadOrGenerate(properties.jwt());
        // kid = RFC 7638 thumbprint: stable for a key, so tokens name the key that signed them and
        // a decoder holding several keys during rotation can pick the right one.
        try {
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyIDFromThumbprint()
                    .build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Could not compute the JWT key ID", e);
        }
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey jwtSigningKey) {
        return new ImmutableJWKSet<>(new JWKSet(jwtSigningKey));
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtSigningKey, AuthProperties properties) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey())
                    .build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Invalid JWT public key", e);
        }
        // Signature is checked by the decoder itself; these add expiry (with a little clock skew so
        // a slightly fast issuer doesn't reject everything), issuer and audience.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.jwt().clockSkew()),
                new JwtIssuerValidator(properties.jwt().issuer()),
                new JwtAudienceValidator(properties.jwt().audience())));
        return decoder;
    }

    static KeyPair loadOrGenerate(AuthProperties.Jwt jwt) {
        if (StringUtils.hasText(jwt.privateKey()) && StringUtils.hasText(jwt.publicKey())) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(stream(jwt.privateKey()));
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(stream(jwt.publicKey()));
            return new KeyPair(publicKey, privateKey);
        }
        if (jwt.generateEphemeralKey()) {
            log.warn("No JWT key configured: generated an ephemeral RSA key pair. Tokens become invalid"
                    + " on restart. Only the local and test profiles may do this.");
            return generate();
        }
        throw new IllegalStateException("No JWT signing key: set reconflow.auth.jwt.private-key and"
                + " reconflow.auth.jwt.public-key (JWT_PRIVATE_KEY, JWT_PUBLIC_KEY) as PEM.");
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available", e);
        }
    }

    private static ByteArrayInputStream stream(String pem) {
        return new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
