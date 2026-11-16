package com.reconflow.paymentreconflow.exceptions.internal.domain;

/**
 * {@code exception_case.status}, exactly the V1 CHECK values (reconciliation-rules.md §7.3). Only
 * {@link ExceptionStateMachine} moves a case between them.
 */
public enum ExceptionStatus {
    OPEN,
    IN_REVIEW,
    RESOLVED;

    /** OPEN or IN_REVIEW: covered by {@code exception_case_active_uk}, at most one per discrepancy key. */
    public boolean isActive() {
        return this != RESOLVED;
    }
}
