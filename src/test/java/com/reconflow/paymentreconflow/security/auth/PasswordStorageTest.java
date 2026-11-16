package com.reconflow.paymentreconflow.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.security.config.PasswordConfig;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordStorageTest {

    private static final PasswordEncoder encoder =
            ReflectionTestUtils.invokeMethod(new PasswordConfig(), "passwordEncoder");
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void encodesWithBcryptCost12AndAnAlgorithmPrefix() {
        String hash = encoder.encode("correct-horse-battery");

        assertThat(hash).startsWith("{bcrypt}$2a$12$");
        assertThat(encoder.matches("correct-horse-battery", hash)).isTrue();
        assertThat(encoder.matches("correct-horse-batterY", hash)).isFalse();
    }

    @Test
    void aHashWithALowerCostIsMarkedForUpgrade() {
        String cost10 = "{bcrypt}" + new BCryptPasswordEncoder(10).encode("correct-horse-battery");
        String cost12 = encoder.encode("correct-horse-battery");

        assertThat(encoder.upgradeEncoding(cost10)).isTrue();
        assertThat(encoder.upgradeEncoding(cost12)).isFalse();
        // The old hash still verifies, so the user can log in once and be rehashed.
        assertThat(encoder.matches("correct-horse-battery", cost10)).isTrue();
    }

    @Test
    void acceptsTwelveCharactersUpTo72Bytes() {
        assertThatCode(() -> policy.validate("a".repeat(12))).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    void rejectsElevenCharacters() {
        assertThatThrownBy(() -> policy.validate("a".repeat(11)))
                .isInstanceOfSatisfying(
                        PasswordPolicyException.class,
                        e -> assertThat(e.code()).isEqualTo(ProblemCodes.PASSWORD_POLICY));
    }

    @Test
    void rejects73BytesCountingUtf8BytesNotCharacters() {
        assertThatThrownBy(() -> policy.validate("a".repeat(73))).isInstanceOf(PasswordPolicyException.class);
        // 19 emoji: 19 characters (would pass a character count) but 76 bytes.
        String emoji = "😀".repeat(19);
        assertThat(PasswordPolicy.utf8Bytes(emoji)).isEqualTo(76);
        assertThatThrownBy(() -> policy.validate(emoji)).isInstanceOf(PasswordPolicyException.class);
    }
}
