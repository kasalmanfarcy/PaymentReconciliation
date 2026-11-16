package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.reconciliation.application.OrphanedRunCheck;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M9.1 for runs: a dead worker's run stays RUNNING while its job goes back to the queue, and the
 * next worker resumes after the checkpoint; out of attempts, job and run fail together, audited; a
 * run with no job is failed as orphaned.
 */
class RunRecoveryIT extends AbstractIntegrationTest {

    static final LocalDate START = CheckpointResumeIT.START;
    static final LocalDate END = CheckpointResumeIT.END;

    @Autowired
    RunDriver driver;

    @Autowired
    ArmableRunHook hook;

    @Autowired
    JobRunner jobRunner;

    @Autowired
    OrphanedRunCheck orphans;

    @Test
    void anExpiredLeaseRequeuesTheJobAndTheRunStaysRunningUntilAnotherWorkerResumesIt() {
        var f = new ReconFixture(jdbc);
        CheckpointResumeIT.tenDaysOfEverything(f);
        UUID run = driver.queueRun(f, START, END, "AED");
        hook.crashOn(run, START.plusDays(4));
        assertThatThrownBy(() -> driver.runQueue(f.org)).isInstanceOf(ArmableRunHook.SimulatedCrash.class);
        hook.disarm(run);
        UUID job = jobOf(f.org);

        driver.expireLeases(f.org);
        jobRunner.sweepExpiredLeases();

        assertThat(jobStatus(job)).isEqualTo("QUEUED");
        assertThat(driver.status(run)).isEqualTo("RUNNING");

        Job resumed = jobRunner
                .claim(jobRunner.workerId("second-worker"), List.of(f.org))
                .orElseThrow();
        jobRunner.execute(resumed);

        assertThat(driver.status(run)).isEqualTo("COMPLETED");
        assertThat(jobStatus(job)).isEqualTo("DONE");
    }

    @Test
    void anExpiredLeaseWithNoAttemptsLeftFailsTheRunWithAReasonAndAnAuditEvent() {
        var f = new ReconFixture(jdbc);
        CheckpointResumeIT.tenDaysOfEverything(f);
        UUID run = driver.queueRun(f, START, END, "AED");
        hook.crashOn(run, START.plusDays(2));
        assertThatThrownBy(() -> driver.runQueue(f.org)).isInstanceOf(ArmableRunHook.SimulatedCrash.class);
        hook.disarm(run);
        jdbc.update("UPDATE job SET attempts = max_attempts WHERE organization_id = ?", f.org);

        driver.expireLeases(f.org);
        jobRunner.sweepExpiredLeases();

        assertThat(jobStatus(jobOf(f.org))).isEqualTo("FAILED");
        assertThat(driver.run(run))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "retries-exhausted")
                .containsEntry("last_completed_date", java.sql.Date.valueOf(START.plusDays(1)));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'RUN_FAILED'",
                        Long.class,
                        run))
                .isEqualTo(1);
    }

    @Test
    void aRunInFlightWithNoLiveJobIsFailedAsOrphaned() {
        var f = new ReconFixture(jdbc);
        UUID run = driver.queueRun(f, START, END, "AED");
        jdbc.update("UPDATE job SET status = 'DONE' WHERE organization_id = ?", f.org);
        jdbc.update("UPDATE reconciliation_run SET updated_at = now() - interval '1 hour' WHERE id = ?", run);

        assertThat(orphans.check(List.of(f.org), Duration.ofMinutes(10))).containsExactly(run);

        assertThat(driver.run(run)).containsEntry("status", "FAILED").containsEntry("failure_reason", "orphaned");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'RUN_FAILED'",
                        Long.class,
                        run))
                .isEqualTo(1);
    }

    @Test
    void aPendingRunWhoseJobIsStillQueuedIsNotAnOrphan() {
        var f = new ReconFixture(jdbc);
        UUID run = driver.queueRun(f, START, END, "AED");
        jdbc.update("UPDATE reconciliation_run SET updated_at = now() - interval '1 hour' WHERE id = ?", run);

        assertThat(orphans.check(List.of(f.org), Duration.ofMinutes(10))).isEmpty();
        assertThat(driver.status(run)).isEqualTo("PENDING");
    }

    private UUID jobOf(UUID org) {
        return jdbc.queryForObject("SELECT id FROM job WHERE organization_id = ?", UUID.class, org);
    }

    private String jobStatus(UUID job) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, job);
    }
}
