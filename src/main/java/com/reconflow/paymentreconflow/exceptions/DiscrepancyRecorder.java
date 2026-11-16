package com.reconflow.paymentreconflow.exceptions;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The exceptions module's API for the matching engine (architecture.md §4): the only way cases are
 * created. Synchronous and in the <em>caller's</em> transaction, so a day-chunk commits its
 * matches, its cases and its checkpoint atomically. A domain event consumed after commit would
 * split that step in two, and a crash between them would lose cases while the checkpoint says the
 * day is done.
 *
 * <p>The dependency points {@code reconciliation → exceptions}, never back: the run id comes in as
 * a parameter.
 */
public interface DiscrepancyRecorder {

    /**
     * For each discrepancy, in key order (one lock order for every transaction, so concurrent runs
     * can't deadlock): create an OPEN case, or re-observe the active case with the same key (K3).
     * Either way, link every record not yet linked, and link the run ({@code exception_case_run}).
     * A key whose only cases are RESOLVED gets a new OPEN case (K4). Idempotent: recording the same
     * discrepancies for the same run twice changes nothing.
     *
     * @param day the anchor day of the chunk that found them, stored on the run link so a
     *     date-range report can attribute each case to the same (run, day) as the run's day totals
     */
    Recorded record(UUID organizationId, UUID runId, LocalDate day, List<Discrepancy> discrepancies);

    /** The cases a run observed, created or re-observed, totalled by type (M6.9's summary). */
    RunExceptionSummary summarize(UUID organizationId, UUID runId);

    /**
     * As {@link #summarize}, for the cases the run observed on one anchor day (M8.1's day rows).
     * Summed over the run's days it equals {@link #summarize}, because a run observes each case on
     * exactly one day.
     */
    RunExceptionSummary summarizeDay(UUID organizationId, UUID runId, LocalDate day);

    /** What {@link #record} did: how many cases it created and how many it found already active. */
    record Recorded(int created, int reobserved) {}
}
