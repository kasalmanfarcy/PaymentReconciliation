package com.reconflow.paymentreconflow.exceptions.internal.domain;

/**
 * Why a case was closed (reconciliation-rules.md §5.3). The column has no CHECK constraint, by the
 * choice recorded there: adding a code is a code change and a docs change, not a migration. Codes
 * whose meaning needs explaining to an auditor require a note.
 */
public enum ResolutionCode {
    TIMING_DIFFERENCE(false),
    GATEWAY_FEE(false),
    DATA_ENTRY_ERROR(true),
    DUPLICATE_CONFIRMED(true),
    DUPLICATE_FILE_ARTIFACT(false),
    WRITTEN_OFF(true),
    FALSE_POSITIVE(true),
    OTHER(true);

    private final boolean noteRequired;

    ResolutionCode(boolean noteRequired) {
        this.noteRequired = noteRequired;
    }

    public boolean noteRequired() {
        return noteRequired;
    }
}
