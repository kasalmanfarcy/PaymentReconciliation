package com.reconflow.paymentreconflow.exceptions;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A command to the exceptions module: "this discrepancy exists, and this run saw it". The engine
 * builds one per non-matching group; {@link DiscrepancyRecorder} turns it into a new case or
 * re-observes the active one with the same key.
 *
 * @param discrepancyKey the problem's identity, built with {@link #key}; no run or period in it (K2)
 * @param reasonCode one of {@link ReasonCodes}, or null
 * @param currency the run's currency (the case's {@code currency} column)
 * @param amount the amount at stake: the smallest ledger amount, else the smallest gateway amount
 * @param amountDifference {@code gateway − ledger}; set for AMOUNT_MISMATCH only
 * @param records every record of the group, ledger first, then gateway
 */
public record Discrepancy(
        ExceptionType type,
        String discrepancyKey,
        @Nullable String reasonCode,
        String currency,
        BigDecimal amount,
        @Nullable BigDecimal amountDifference,
        List<RecordRef> records) {

    public Discrepancy {
        Objects.requireNonNull(type);
        Objects.requireNonNull(discrepancyKey);
        Objects.requireNonNull(amount);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("A discrepancy links at least one record");
        }
        records = List.copyOf(records);
    }

    /**
     * {@code <TYPE>:<normalized_reference>:<transaction_type>:<sorted currencies joined by '/'>}
     * (reconciliation-rules.md §6, K1). The currency part is the <em>sorted set</em>, so the AED
     * run and the USD run compute the same {@code …:AED/USD} key for one currency mismatch (K2).
     * A reference may contain ':'; nothing is escaped, because the other three fields never do,
     * so a parser reads the first field from the left and the last two from the right.
     */
    public static String key(
            ExceptionType type, String normalizedReference, String transactionType, Collection<String> currencies) {
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("A discrepancy key needs at least one currency");
        }
        return type.name() + ":" + normalizedReference + ":" + transactionType + ":"
                + String.join("/", new TreeSet<>(currencies));
    }
}
