package com.reconflow.paymentreconflow.ingestion.domain;

/**
 * The import batch state machine (reconciliation-rules.md §7.1). Values match the CHECK on
 * {@code import_batch.status}.
 */
public enum ImportBatchStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED;
    }
}
