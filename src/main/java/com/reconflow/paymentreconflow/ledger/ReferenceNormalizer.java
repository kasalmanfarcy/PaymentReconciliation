package com.reconflow.paymentreconflow.ledger;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Rules N1–N4 (reconciliation-rules.md §1): the canonical form of a payment reference. Public in
 * ledger because both the importer and transaction search ({@code ?reference=pay 103}) must apply
 * exactly the same rules, or a search would miss what the import stored.
 */
public final class ReferenceNormalizer {

    /** Longest normalized reference accepted (REFERENCE_TOO_LONG above it). */
    public static final int MAX_LENGTH = 128;

    private ReferenceNormalizer() {}

    public static String normalize(String raw) {
        // N2 first: NFKC turns full-width characters (ＰＡＹ) and the non-breaking space U+00A0
        // into their plain forms, so N1's strip() then removes a leading NBSP too.
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        // N1: strip(), not trim(): trim() only removes characters <= U+0020.
        // N3: Locale.ROOT, never the JVM default: on a Turkish-locale JVM "i".toUpperCase() is the
        // dotted İ, and the reference would silently stop matching.
        // N4: nothing inside the reference is removed or changed: PAY-101 and PAY101 stay different,
        // because a false match hides a discrepancy, which is worse than a missed one.
        return s.strip().toUpperCase(Locale.ROOT);
    }
}
