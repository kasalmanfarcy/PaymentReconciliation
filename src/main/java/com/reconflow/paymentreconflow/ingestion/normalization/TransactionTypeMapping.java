package com.reconflow.paymentreconflow.ingestion.normalization;

import com.reconflow.paymentreconflow.ledger.TransactionType;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Rule N10: trim and upper-case; one of the four types; blank or no column means PAYMENT. */
public final class TransactionTypeMapping {

    private TransactionTypeMapping() {}

    /** Empty for an unknown value (UNKNOWN_TRANSACTION_TYPE). */
    public static Optional<TransactionType> map(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(TransactionType.PAYMENT);
        }
        try {
            return Optional.of(TransactionType.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
