package com.reconflow.paymentreconflow.ingestion.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The import batch state machine (reconciliation-rules.md §7.1), in exactly one place (M5.15).
 * Values match the CHECK on {@code import_batch.status}; anything not in {@link #ALLOWED} is
 * rejected with {@code 409 invalid-transition}.
 *
 * <p>{@code PROCESSING → PROCESSING} (a resumed job) is deliberately absent: resuming isn't a
 * status change, and adding it to "make it work" would also allow nonsense.
 */
public enum ImportBatchStatus {
    RECEIVED,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED;

    private static final Map<ImportBatchStatus, Set<ImportBatchStatus>> ALLOWED = Map.of(
            // FAILED from RECEIVED: the job ran out of attempts before processing ever started.
            RECEIVED, EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(COMPLETED, COMPLETED_WITH_ERRORS, FAILED),
            FAILED, EnumSet.of(RECEIVED), // explicit retry only (M5.20)
            COMPLETED, EnumSet.noneOf(ImportBatchStatus.class),
            COMPLETED_WITH_ERRORS, EnumSet.noneOf(ImportBatchStatus.class));

    public boolean canTransitionTo(ImportBatchStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED;
    }
}
