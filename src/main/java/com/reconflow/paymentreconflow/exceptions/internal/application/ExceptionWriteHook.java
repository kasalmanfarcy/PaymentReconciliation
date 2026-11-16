package com.reconflow.paymentreconflow.exceptions.internal.application;

import java.util.UUID;

/**
 * A seam for the concurrency and rollback tests (M7.12, M7.14), like M6's RunChunkHook: called
 * inside each versioned write's transaction. Production's implementation does nothing; a test
 * replaces it to hold two requests at a barrier after both passed the If-Match compare (making the
 * {@code @Version} race certain rather than likely), or to fail after the change was applied.
 */
public interface ExceptionWriteHook {

    /** After the case was loaded and its version matched If-Match, before anything changed. */
    void afterVersionCheck(UUID caseId);

    /** After the change, its history and audit were flushed, before commit. */
    void beforeCommit(UUID caseId);
}
