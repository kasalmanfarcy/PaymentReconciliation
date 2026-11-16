package com.reconflow.paymentreconflow.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtKeyConfigTest {

    private static AuthProperties.Jwt jwt(String privateKey, String publicKey, boolean ephemeral) {
        return new AuthProperties.Jwt(
                "reconflow",
                "reconflow-api",
                Duration.ofMinutes(15),
                Duration.ofSeconds(60),
                privateKey,
                publicKey,
                ephemeral);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void failsFastWithoutAKeyOutsideLocalAndTest() {
        assertThatIllegalStateException()
                .isThrownBy(() -> JwtKeyConfig.loadOrGenerate(jwt(null, null, false)))
                .withMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    void loadsAPemKeyPairFromConfiguration() {
        // Generated here, never committed: the repository holds no private key.
        KeyPair generated = JwtKeyConfig.generate();
        String privatePem = pem("PRIVATE KEY", generated.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", generated.getPublic().getEncoded());

        KeyPair loaded = JwtKeyConfig.loadOrGenerate(jwt(privatePem, publicPem, false));

        assertThat(loaded.getPublic()).isEqualTo(generated.getPublic());
        assertThat(loaded.getPrivate().getEncoded())
                .isEqualTo(generated.getPrivate().getEncoded());
    }

    @Test
    void generatesAnEphemeralPairWhenAllowed() {
        assertThat(JwtKeyConfig.loadOrGenerate(jwt(null, null, true))
                        .getPublic()
                        .getAlgorithm())
                .isEqualTo("RSA");
    }
}
