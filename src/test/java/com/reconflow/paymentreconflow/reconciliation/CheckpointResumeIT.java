package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.9: each day commits its matches, cases and checkpoint together. A worker killed after day 3 of
 * a 10-day run, resumed by another, ends in exactly the state of an uninterrupted run; a zombie that
 * wakes up after its lease was taken writes nothing.
 */
class CheckpointResumeIT extends AbstractIntegrationTest {

    static final LocalDate START = LocalDate.of(2026, 10, 1);
    static final LocalDate END = START.plusDays(9);

    @Autowired
    RunDriver driver;

    @Autowired
    ArmableRunHook hook;

    @Autowired
    JobRunner jobRunner;

    /** Every outcome on every day, including groups that cross days. */
    static void tenDaysOfEverything(ReconFixture f) {
        for (int i = 0; i < 10; i++) {
            LocalDate d = START.plusDays(i);
            f.ledger("M-" + i, "100.00", "AED", d);
            f.gateway("M-" + i, "100.0", "AED", d);
            f.ledger("X-" + i, "50.00", "AED", d); // the gateway half lands the next day
            f.gateway("X-" + i, "50.00", "AED", d.plusDays(1));
            f.ledger("A-" + i, "75.00", "AED", d);
            f.gateway("A-" + i, "70.00", "AED", d);
            f.ledger("G-" + i, "10.00", "AED", d);
            f.gateway("L-" + i, "20.00", "AED", d);
            f.gateway("D-" + i, "5.00", "AED", d);
            f.gateway("D-" + i, "5.00", "AED", d.plusDays(2));
            f.gateway("C-" + i, "9.00", "USD", d);
            f.ledger("C-" + i, "9.00", "AED", d);
            f.record(SourceType.GATEWAY, "F-" + i, "8.00", "AED", d, "FAILED", "PAYMENT", false);
        }
    }

    @Test
    void aWorkerKilledAfterDay3ResumesToTheCleanResult() {
        var f = new ReconFixture(jdbc);
        tenDaysOfEverything(f);
        UUID crashed = driver.queueRun(f, START, END, "AED");
        hook.crashOn(crashed, START.plusDays(3)); // days 1-3 commit, day 4 dies mid-transaction

        assertThatThrownBy(() -> driver.runQueue(f.org)).isInstanceOf(ArmableRunHook.SimulatedCrash.class);
        assertThat(driver.run(crashed))
                .containsEntry("status", "RUNNING")
                .containsEntry("last_completed_date", java.sql.Date.valueOf(START.plusDays(2)));

        hook.disarm(crashed);
        driver.expireLeases(f.org);
        jobRunner.sweepExpiredLeases();
        driver.runQueue(f.org);

        UUID clean = driver.run(f, START, END, "AED"); // same organization: same records, same cases
        assertSameOutcome(crashed, clean);
        assertThat(driver.summary(crashed)).containsEntry("status", "COMPLETED");
        assertThat(count("SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'RUN_STARTED'", crashed))
                .as("resuming is not a transition")
                .isEqualTo(1);
    }

    @Test
    void aZombieWhoseLeaseWasTakenWritesNothingAndTheSuccessorFinishes() {
        var f = new ReconFixture(jdbc);
        tenDaysOfEverything(f);
        UUID run = driver.queueRun(f, START, END, "AED");
        hook.crashOn(run, START.plusDays(1)); // worker A stalls on day 2 (a GC pause, a partition)
        Job zombie = jobRunner.claim(jobRunner.workerId("A"), List.of(f.org)).orElseThrow();
        assertThatThrownBy(() -> jobRunner.execute(zombie)).isInstanceOf(ArmableRunHook.SimulatedCrash.class);
        hook.disarm(run);

        // A's lease expires; the sweeper re-queues the job and worker B claims it.
        driver.expireLeases(f.org);
        jobRunner.sweepExpiredLeases();
        Job successor = jobRunner.claim(jobRunner.workerId("B"), List.of(f.org)).orElseThrow();

        // A wakes up and carries on: its first fenced transaction fails, nothing is written.
        jobRunner.execute(zombie);
        assertThat(driver.run(run))
                .containsEntry("status", "RUNNING")
                .containsEntry("last_completed_date", java.sql.Date.valueOf(START));

        jobRunner.execute(successor);

        UUID clean = driver.run(f, START, END, "AED");
        assertSameOutcome(run, clean);
    }

    @Test
    void anEmptyPeriodCompletesWithZeros() {
        var f = new ReconFixture(jdbc);
        UUID run = driver.run(f, START, END, "AED");

        assertThat(driver.summary(run))
                .containsEntry("status", "COMPLETED")
                .containsEntry("matched_count", 0)
                .containsEntry("matched_amount", "0.0000")
                .containsEntry("duplicate_count", 0)
                .containsEntry("excluded_count", 0)
                .containsEntry("last_completed_date", java.sql.Date.valueOf(END));
    }

    @Test
    void aRunWaitsWithoutSpendingAttemptsWhileAnImportIsInProgress() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", START);
        UUID importing = f.batch("GATEWAY", "PROCESSING");
        UUID run = driver.queueRun(f, START, START, "AED");

        driver.runQueue(f.org);

        assertThat(driver.status(run)).isEqualTo("PENDING");
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, attempts, run_after > now() AS later FROM job WHERE organization_id = ?", f.org);
        assertThat(job)
                .containsEntry("status", "QUEUED")
                .containsEntry("attempts", 0)
                .containsEntry("later", true);

        jdbc.update("UPDATE import_batch SET status = 'COMPLETED' WHERE id = ?", importing);
        jdbc.update("UPDATE job SET run_after = now() WHERE organization_id = ?", f.org);
        driver.runQueue(f.org);

        assertThat(driver.status(run)).isEqualTo("COMPLETED");
    }

    private void assertSameOutcome(UUID run, UUID clean) {
        assertThat(driver.matchedPairs(run)).isNotEmpty().isEqualTo(driver.matchedPairs(clean));
        assertThat(driver.observedCases(run)).isNotEmpty().isEqualTo(driver.observedCases(clean));
        Map<String, Object> expected = driver.summary(clean);
        assertThat(driver.summary(run)).isEqualTo(expected);
        // M8.1: the day rows roll back with their chunk, so a resumed run has exactly one per day.
        assertThat(driver.dayRows(run)).hasSize(10).isEqualTo(driver.dayRows(clean));
        assertThat(count(
                        "SELECT count(*) FROM (SELECT ledger_record_id FROM reconciliation_match WHERE run_id = ?"
                                + " GROUP BY 1 HAVING count(*) > 1) d",
                        run))
                .isZero();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
