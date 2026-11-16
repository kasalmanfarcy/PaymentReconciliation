package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.ScriptedFaultInjector;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * M9.3 for runs: a day-chunk that fails transiently is retried in-process as a new transaction;
 * one whose commit landed before the error is recognised by its checkpoint and not redone.
 */
class RunTransientRetryIT extends AbstractIntegrationTest {

    @Autowired
    RunDriver driver;

    @Autowired
    ScriptedFaultInjector faults;

    @AfterEach
    void clear() {
        faults.clear();
    }

    @Test
    void aDayWhoseTransactionFailsTransientlyIsRetriedAndCountedOnce() {
        var f = new ReconFixture(jdbc);
        CheckpointResumeIT.tenDaysOfEverything(f);
        faults.onHit(FaultPoints.EXCEPTIONS_AFTER_CASE_INSERT, 7, () -> {
            throw new CannotAcquireLockException("simulated 40P01 deadlock victim");
        });

        UUID run = driver.run(f, CheckpointResumeIT.START, CheckpointResumeIT.END, "AED");
        faults.clear();

        assertCleanRun(f, run);
    }

    @Test
    void aDayWhoseCommitLandedBeforeTheErrorIsNotRedone() {
        var f = new ReconFixture(jdbc);
        CheckpointResumeIT.tenDaysOfEverything(f);
        faults.onHit(
                FaultPoints.EXCEPTIONS_AFTER_CASE_INSERT,
                7,
                () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new CannotAcquireLockException("connection lost after COMMIT was sent");
                    }
                }));

        UUID run = driver.run(f, CheckpointResumeIT.START, CheckpointResumeIT.END, "AED");
        faults.clear();

        assertCleanRun(f, run);
    }

    private void assertCleanRun(ReconFixture f, UUID run) {
        assertThat(driver.status(run)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                        "SELECT attempts FROM job WHERE payload ->> 'runId' = ?", Integer.class, run.toString()))
                .isEqualTo(1);
        UUID clean = driver.run(f, CheckpointResumeIT.START, CheckpointResumeIT.END, "AED"); // same records
        assertThat(driver.matchedPairs(run)).isNotEmpty().isEqualTo(driver.matchedPairs(clean));
        assertThat(driver.observedCases(run)).isNotEmpty().isEqualTo(driver.observedCases(clean));
        assertThat(driver.summary(run)).isEqualTo(driver.summary(clean));
        assertThat(driver.dayRows(run)).hasSize(10).isEqualTo(driver.dayRows(clean));
    }
}
