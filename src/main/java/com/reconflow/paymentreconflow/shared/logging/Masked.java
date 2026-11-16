package com.reconflow.paymentreconflow.shared.logging;

import org.jspecify.annotations.Nullable;

/**
 * Masking for log lines (M10.6, docs/security.md §4). Applied at the call site, because only the
 * code knows that {@code PAY-0001234} is a transaction reference; a regex in the logging pipeline
 * can't. Keep the UUIDs next to a masked value: they let an operator look up the real one in the
 * database, under its access control.
 */
public final class Masked {

    private static final String STARS = "****";

    private Masked() {}

    /** {@code PAY-0001234} → {@code ****1234}; four characters or fewer → {@code ****}. */
    public static @Nullable String reference(@Nullable String reference) {
        if (reference == null) {
            return null;
        }
        return reference.length() <= 4 ? STARS : STARS + reference.substring(reference.length() - 4);
    }

    /** {@code jane@example.com} → {@code j***@example.com}; no usable local part → {@code ***@domain}. */
    public static @Nullable String email(@Nullable String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at < 0) {
            return "***";
        }
        return at <= 1 ? "***" + email.substring(at) : email.charAt(0) + "***" + email.substring(at);
    }
}
