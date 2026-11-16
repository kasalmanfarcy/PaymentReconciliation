package com.reconflow.paymentreconflow.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * A stand-in OpenID Connect issuer for the integration tests (M10.1): a tiny HTTP server on a
 * random local port that serves the two documents a resource server needs, the discovery document
 * and the JWKS, and signs tokens with a key generated at startup (nothing committed).
 *
 * <p>Why not a {@code jwt()} request post-processor or a mocked JwtDecoder: those skip the very
 * code the security of the API depends on. With this, every test request goes through Boot's real
 * issuer-uri decoder: discovery, JWKS fetch, signature, {@code exp}, {@code iss}, {@code aud}. The
 * handful of tests that need the real Keycloak (KeycloakIT) start the container instead.
 *
 * <p>One per JVM, shared by every Spring context, like the containers.
 */
public final class TestIssuer {

    private static final TestIssuer INSTANCE = start();

    private final HttpServer server;
    private final String issuer;
    private final RSAKey key;

    private TestIssuer(HttpServer server, RSAKey key) {
        this.server = server;
        this.key = key;
        this.issuer = "http://localhost:" + server.getAddress().getPort() + "/realms/test";
    }

    public static TestIssuer instance() {
        return INSTANCE;
    }

    /** The {@code iss} of every token, and the {@code issuer-uri} the application is given. */
    public String issuer() {
        return issuer;
    }

    /** Signs with the issuer's key (the one its JWKS publishes). */
    public JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    /** A key pair the issuer never published: tokens signed with it must be rejected. */
    public static JwtEncoder foreignEncoder() {
        KeyPair other = generate();
        return NimbusJwtEncoder.withKeyPair((RSAPublicKey) other.getPublic(), (RSAPrivateKey) other.getPrivate())
                .build();
    }

    private static TestIssuer start() {
        try {
            KeyPair pair = generate();
            RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            TestIssuer issuer = new TestIssuer(server, key);
            server.createContext(
                    "/realms/test/.well-known/openid-configuration",
                    exchange -> respond(exchange, """
                    {"issuer":"%1$s","jwks_uri":"%1$s/protocol/openid-connect/certs",\
                    "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"]}""".formatted(issuer.issuer)));
            server.createContext(
                    "/realms/test/protocol/openid-connect/certs",
                    exchange -> respond(exchange, new JWKSet(key.toPublicJWK()).toString()));
            server.setExecutor(null);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return issuer;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the test OIDC issuer", e);
        }
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
