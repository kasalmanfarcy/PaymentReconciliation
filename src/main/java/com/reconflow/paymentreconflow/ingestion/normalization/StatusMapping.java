package com.reconflow.paymentreconflow.ingestion.normalization;

import com.reconflow.paymentreconflow.ledger.TransactionStatus;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Rule N9: source status words to the four normalized statuses, case-insensitively after
 * trimming. The table is reconciliation-rules.md §1 exactly; a source-specific synonym that isn't
 * in it (a gateway writing {@code sale}) is a per-organization mapping in v2, not a line here.
 */
public final class StatusMapping {

    private static final Map<String, TransactionStatus> SYNONYMS = Map.ofEntries(
            Map.entry("success", TransactionStatus.SUCCESS),
            Map.entry("succeeded", TransactionStatus.SUCCESS),
            Map.entry("captured", TransactionStatus.SUCCESS),
            Map.entry("settled", TransactionStatus.SUCCESS),
            Map.entry("paid", TransactionStatus.SUCCESS),
            Map.entry("completed", TransactionStatus.SUCCESS),
            Map.entry("pending", TransactionStatus.PENDING),
            Map.entry("processing", TransactionStatus.PENDING),
            Map.entry("authorized", TransactionStatus.PENDING),
            Map.entry("failed", TransactionStatus.FAILED),
            Map.entry("declined", TransactionStatus.FAILED),
            Map.entry("error", TransactionStatus.FAILED),
            Map.entry("reversed", TransactionStatus.REVERSED),
            Map.entry("refunded", TransactionStatus.REVERSED),
            Map.entry("voided", TransactionStatus.REVERSED),
            Map.entry("cancelled", TransactionStatus.REVERSED));

    private StatusMapping() {}

    public static Optional<TransactionStatus> map(String raw) {
        return Optional.ofNullable(SYNONYMS.get(raw.strip().toLowerCase(Locale.ROOT)));
    }
}
