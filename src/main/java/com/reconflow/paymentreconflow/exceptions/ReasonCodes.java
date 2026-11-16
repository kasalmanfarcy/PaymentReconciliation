package com.reconflow.paymentreconflow.exceptions;

/**
 * {@code exception_case.reason_code} values (reconciliation-rules.md §5.2): an optional
 * sub-classification the engine sets when it knows more than the type says. No CHECK constraint;
 * the engine is the only writer.
 */
public final class ReasonCodes {

    private ReasonCodes() {}

    /** Missing types: the other source has the reference in the period, but not as SUCCESS (rule P4). */
    public static final String COUNTERPART_NOT_SUCCESS = "COUNTERPART_NOT_SUCCESS";

    /** Duplicates: every record in the group has the same amount. */
    public static final String SAME_AMOUNT = "SAME_AMOUNT";

    /** Duplicates: at least two different amounts in the group. */
    public static final String DIFFERENT_AMOUNTS = "DIFFERENT_AMOUNTS";
}
