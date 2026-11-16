package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What makes two run requests "the same run" (ADR 0006, api-design.md §5).
 *
 * <ul>
 *   <li>The <em>fingerprint</em> is the SHA-256 of the canonical request,
 *       {@code org|periodStart|periodEnd|leftSource|rightSource|currency}. Stored on every run, it
 *       detects an {@code Idempotency-Key} reused with a different body.</li>
 *   <li>The stored <em>key</em> is {@code client:<header>} when the client sent one, otherwise
 *       {@code auto:<fingerprint>}. The prefixes keep the two namespaces apart, so a client can't
 *       send a key that collides with a derived one.</li>
 * </ul>
 */
public final class RunKeys {

    /** api-design.md §5: 1–128 printable ASCII characters (the plan said 255; the docs win). */
    static final Pattern CLIENT_KEY = Pattern.compile("[\\x20-\\x7E]{1,128}");

    static final String CLIENT_PREFIX = "client:";
    static final String AUTO_PREFIX = "auto:";

    private RunKeys() {}

    public static String fingerprint(
            UUID organizationId,
            LocalDate periodStart,
            LocalDate periodEnd,
            SourceType leftSource,
            SourceType rightSource,
            String currency) {
        String canonical = String.join(
                "|",
                organizationId.toString(),
                periodStart.toString(),
                periodEnd.toString(),
                leftSource.name(),
                rightSource.name(),
                currency);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** The stored idempotency key for a request with this header (or none) and fingerprint. */
    public static String key(@Nullable String clientKey, String fingerprint) {
        return clientKey == null ? AUTO_PREFIX + fingerprint : CLIENT_PREFIX + clientKey;
    }

    static boolean isValidClientKey(String clientKey) {
        return CLIENT_KEY.matcher(clientKey).matches();
    }
}
