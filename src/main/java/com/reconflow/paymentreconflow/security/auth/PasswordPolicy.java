package com.reconflow.paymentreconflow.security.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * NIST 800-63B style: a minimum length and nothing else. No composition rules ("one digit, one
 * symbol") and no forced rotation; both push people towards predictable passwords.
 *
 * <p>The maximum is 72 <em>bytes</em> of UTF-8, not characters, because bcrypt silently ignores
 * everything after byte 72: without the cap, two long passwords sharing their first 72 bytes would
 * authenticate each other. An emoji is 4 bytes, so counting characters would let 19 of them
 * (76 bytes) through.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_UTF8_BYTES = 72;

    public void validate(String raw) {
        if (raw == null || raw.codePointCount(0, raw.length()) < MIN_CHARACTERS) {
            throw new PasswordPolicyException("The password must be at least " + MIN_CHARACTERS + " characters long.");
        }
        if (utf8Bytes(raw) > MAX_UTF8_BYTES) {
            throw new PasswordPolicyException("The password must be at most " + MAX_UTF8_BYTES + " bytes of UTF-8.");
        }
    }

    static int utf8Bytes(String raw) {
        return raw.getBytes(StandardCharsets.UTF_8).length;
    }
}
