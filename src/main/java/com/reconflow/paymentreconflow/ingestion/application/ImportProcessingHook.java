package com.reconflow.paymentreconflow.ingestion.application;

import java.util.UUID;

/**
 * Seams for failure-injection tests (M5.25): a test replaces the no-op bean with one that throws
 * after row N, or just before the finishing transaction commits, to prove a crash at that point
 * changes nothing a resumed run can't repair. Production uses {@link NoopImportProcessingHook}.
 */
public interface ImportProcessingHook {

    /** After row {@code rowNumber} is validated and buffered, before its chunk commits. */
    void afterRow(UUID batchId, int rowNumber);

    /** Inside the finishing transaction, after the duplicate flags are set, before it commits. */
    void beforeFinishCommit(UUID batchId);
}
