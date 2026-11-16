package com.reconflow.paymentreconflow.reconciliation.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A seam for failure injection (M6.9, M6.12): called inside each day-chunk's transaction after the
 * day's matches and cases are written and before the checkpoint advances. Production's
 * implementation does nothing; tests replace it to crash or fail on a chosen day, as M5's
 * ImportProcessingHook does for imports.
 */
public interface RunChunkHook {

    void beforeCheckpoint(UUID runId, LocalDate day);
}
