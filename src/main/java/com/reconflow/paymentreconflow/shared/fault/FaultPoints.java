package com.reconflow.paymentreconflow.shared.fault;

import java.util.List;

/** Every point a {@link FaultInjector} is called at, and what a crash there simulates. */
public final class FaultPoints {

    /** Inside a chunk's transaction, rows written, before it commits. */
    public static final String IMPORT_BEFORE_CHUNK_COMMIT = "import.before-chunk-commit";
    /** Right after a chunk committed (its checkpoint with it), before the next chunk starts. */
    public static final String IMPORT_AFTER_CHUNK_COMMIT = "import.after-chunk-commit";
    /** Every row committed, the batch not yet COMPLETED. */
    public static final String IMPORT_AFTER_LAST_CHUNK = "import.after-last-chunk";
    /** One day's matches, cases and checkpoint committed; the next day not started. */
    public static final String RECONCILE_AFTER_DAY_COMMIT = "reconcile.after-day-commit";
    /** Every day done, the run's totals and COMPLETED status not yet written. */
    public static final String RECONCILE_BEFORE_SUMMARY = "reconcile.before-summary";
    /** A new exception case inserted, its links and first history row not yet written (same transaction). */
    public static final String EXCEPTIONS_AFTER_CASE_INSERT = "exceptions.after-case-insert";
    /** Before an object-storage upload or download: where an outage shows up. */
    public static final String STORAGE_PUT = "storage.put";

    public static final String STORAGE_DOWNLOAD = "storage.download";

    /** The crash points of the M9.4 failure-injection suite, in processing order. */
    public static final List<String> CRASH_POINTS = List.of(
            IMPORT_BEFORE_CHUNK_COMMIT,
            IMPORT_AFTER_CHUNK_COMMIT,
            IMPORT_AFTER_LAST_CHUNK,
            EXCEPTIONS_AFTER_CASE_INSERT,
            RECONCILE_AFTER_DAY_COMMIT,
            RECONCILE_BEFORE_SUMMARY);

    private FaultPoints() {}
}
