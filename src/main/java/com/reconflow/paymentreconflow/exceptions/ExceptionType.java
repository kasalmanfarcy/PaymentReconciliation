package com.reconflow.paymentreconflow.exceptions;

/**
 * The closed list of discrepancy types (reconciliation-rules.md §5.1), exactly the CHECK on
 * {@code exception_case.type}. Named by <em>source</em>, not by side: a ledger-only group is
 * {@link #MISSING_IN_GATEWAY} whichever source is the run's left one.
 */
public enum ExceptionType {
    MISSING_IN_GATEWAY,
    MISSING_IN_LEDGER,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_IN_SOURCE
}
