package com.reconflow.paymentreconflow.reconciliation.engine;

import java.time.LocalDate;

/**
 * Classifies one day-chunk of a run (ADR 0012). v1 has one implementation,
 * {@link ExactReferenceStrategy}; v2's fee-aware and one-to-many matching add others beside it,
 * without touching the run lifecycle, chunking, checkpoints or exception recording.
 *
 * <p>A strategy never owns transactions or checkpoints: the executor does. It only reads and
 * writes inside the transaction it is given, with the tenant already bound for row-level security.
 */
public interface MatchingStrategy {

    /** Stable name, for logs and, in later versions, the match type. */
    String name();

    /**
     * Classifies every group anchored on {@code day} (the earliest in-period date among the group's
     * participating records), writes its matches, and returns the discrepancies for the caller to
     * hand to the exceptions module. Must run inside a transaction.
     */
    DayResult reconcileDay(RunContext run, LocalDate day);
}
