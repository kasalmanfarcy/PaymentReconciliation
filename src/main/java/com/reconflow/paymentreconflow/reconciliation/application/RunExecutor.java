package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.exceptions.DiscrepancyRecorder;
import com.reconflow.paymentreconflow.reconciliation.engine.DayResult;
import com.reconflow.paymentreconflow.reconciliation.engine.MatchingStrategy;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.shared.fault.FaultInjector;
import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobContext;
import com.reconflow.paymentreconflow.shared.jobs.JobDeferredException;
import com.reconflow.paymentreconflow.shared.jobs.JobStoppedException;
import com.reconflow.paymentreconflow.shared.jobs.TransientRetry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker's run loop (M6.9, ADR 0012): one transaction per day of the period, each committing
 * that day's matches, its exception cases, its day totals (M8.1) and the {@code last_completed_date}
 * checkpoint together.
 * A crash loses at most the day in flight; the next attempt resumes after the checkpoint and ends
 * in exactly the state of a clean run.
 *
 * <p>Exactly-once effects from at-least-once execution: the job may run twice (crash, expired
 * lease). The checkpoint commits atomically with the day's output, so resuming is exact; unique
 * constraints with {@code ON CONFLICT DO NOTHING} on matches and case links are the guard behind
 * it; and every chunk is fenced twice, on the job's lease and on the run's checkpoint, so a zombie
 * worker can't commit.
 *
 * <p>TransactionTemplate, not {@code @Transactional}: the annotation works through a proxy, so a
 * loop calling {@code this.processDay()} would get no transaction at all (or one huge one). Here
 * each chunk's boundary is explicit.
 */
@Component
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    /** How long a run waits for an organization's import to finish before looking again. */
    static final Duration IMPORT_WAIT = Duration.ofMinutes(1);

    private final TransactionTemplate tx;
    private final RunLifecycle lifecycle;
    private final MatchingStrategy strategy;
    private final DiscrepancyRecorder discrepancies;
    private final RunChunkHook hook;
    private final TransientRetry retry;
    private final FaultInjector faults;

    RunExecutor(
            TransactionTemplate tx,
            RunLifecycle lifecycle,
            MatchingStrategy strategy,
            DiscrepancyRecorder discrepancies,
            RunChunkHook hook,
            TransientRetry retry,
            FaultInjector faults) {
        this.tx = tx;
        this.lifecycle = lifecycle;
        this.strategy = strategy;
        this.discrepancies = discrepancies;
        this.hook = hook;
        this.retry = retry;
        this.faults = faults;
    }

    /** Runs (or resumes) the job's run. The job's organization is already bound (JobRunner). */
    public void execute(Job job, JobContext context) {
        UUID organizationId = job.organizationId();
        UUID runId = job.uuid(ReconcileJobs.RUN_ID);
        // Every transaction below is wrapped by the in-process retry (M9.3), never the reverse.
        RunLifecycle.Start start = retry.call(
                "reconcile.start",
                () -> tx.execute(s -> {
                    context.fence();
                    return lifecycle.start(organizationId, runId);
                }));
        switch (start) {
            case RunLifecycle.Start.Skip skip -> {} // the runner marks the job DONE
            case RunLifecycle.Start.Defer defer -> throw new JobDeferredException(IMPORT_WAIT, "import-in-progress");
            case RunLifecycle.Start.Run run -> runDays(run.context(), run.firstDay(), context);
        }
    }

    private void runDays(RunContext run, LocalDate firstDay, JobContext context) {
        for (LocalDate day = firstDay; !day.isAfter(run.periodEnd()); day = day.plusDays(1)) {
            LocalDate d = day;
            context.ensureLeaseHeld();
            DayResult result = retry.call(
                    "reconcile.day",
                    () -> tx.execute(s -> {
                        if (!lifecycle.lockForChunk(run, d)) { // fence 1: the checkpoint is where we expect
                            context.fence(); // ...and it was our own retried commit that moved it
                            return DayResult.ALREADY_COMMITTED;
                        }
                        DayResult r = strategy.reconcileDay(run, d);
                        discrepancies.record(run.organizationId(), run.runId(), d, r.discrepancies());
                        lifecycle.recordDay(run, d, r); // M8.1: the day's totals, for date-range reports
                        hook.beforeCheckpoint(run.runId(), d);
                        // Fence 2, last (M9.7): we still hold the job's lease, checked in this
                        // transaction, so a zombie still can't commit. Last, because the fence locks
                        // the job row until commit: taken first, it blocked the heartbeat thread for
                        // the whole chunk (docs/benchmarks.md §2.2).
                        context.fence();
                        lifecycle.advanceCheckpoint(run, d);
                        return r;
                    }));
            faults.at(FaultPoints.RECONCILE_AFTER_DAY_COMMIT);
            log.debug(
                    "Run {} day {}: {} matches, {} discrepancies",
                    run.runId(),
                    d,
                    result.matchesInserted(),
                    result.discrepancies().size());
            if (context.stopRequested() && d.isBefore(run.periodEnd())) {
                throw new JobStoppedException(); // hand back at a chunk boundary
            }
        }
        faults.at(FaultPoints.RECONCILE_BEFORE_SUMMARY);
        // The summary, the COMPLETED status, its audit event and the job's DONE: all or nothing. A
        // retry after a commit that did land fails the fence (the job is DONE): nothing is redone.
        retry.run(
                "reconcile.complete",
                () -> tx.executeWithoutResult(s -> {
                    context.fence();
                    lifecycle.complete(run);
                    context.complete();
                }));
    }
}
