package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.reconciliation.domain.MatchType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One match with both records side by side (api-design.md §8.11). */
public record MatchView(
        UUID id, MatchType matchType, String currency, BigDecimal amountDifference, Side ledger, Side gateway) {

    /** A matched record: the raw reference (what the file said) and the normalized one. */
    public record Side(
            UUID id, String reference, String normalizedReference, BigDecimal amount, LocalDate transactionDate) {}
}
