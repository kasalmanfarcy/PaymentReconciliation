package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.ingestion.application.OrphanedImportCheck;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M9.1: nothing stays stuck in {@code PROCESSING}. The job row says whether work is alive; the batch
 * says how far it got. A dead worker's job goes back to the queue with the batch untouched (users
 * must never see FAILED, then COMPLETED); out of attempts, both fail together with a reason and an
 * audit event; a batch with no job at all is found and failed by the consistency check.
 */
class ImportRecoveryIT extends AbstractImportIT {

    @Autowired
    ArmableImportHook hook;

    @Autowired
    OrphanedImportCheck orphans;

    @Autowired
    MeterRegistry meters;

    @Test
    void anExpiredLeaseRequeuesTheJobWithoutTouchingTheBatchAndAnotherWorkerResumesIt() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", ImportRetryIT.thousandRows(), "INTERNAL_LEDGER");
        hook.crashAfterRow(batch, 350);
        assertThatThrownBy(() -> runQueue(t)).isInstanceOf(ArmableImportHook.SimulatedCrash.class);
        hook.disarm(batch);
        UUID job = jobOf(t);

        expireLeases(t);
        jobRunner.sweepExpiredLeases();

        assertThat(jobRow(job, "status")).isEqualTo("QUEUED");
        assertThat(batchRow(batch))
                .as("the batch has no lease of its own; it waits for its job")
                .containsEntry("status", "PROCESSING")
                .containsEntry("last_committed_row", 300);

        Job resumed = jobRunner
                .claim(jobRunner.workerId("second-worker"), List.of(t.org()))
                .orElseThrow();
        jobRunner.execute(resumed);

        assertThat(batchRow(batch))
                .containsEntry("status", "COMPLETED_WITH_ERRORS")
                .containsEntry("total_rows", 1000);
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isEqualTo(1000 - 1000 / 7);
        assertThat(jobRow(job, "status")).isEqualTo("DONE");
        assertThat(jobRow(job, "attempts")).isEqualTo(2);
    }

    @Test
    void anExpiredLeaseWithNoAttemptsLeftFailsTheJobAndTheBatchTogetherWithAnAuditEvent() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", ImportRetryIT.thousandRows(), "INTERNAL_LEDGER");
        hook.crashAfterRow(batch, 150);
        assertThatThrownBy(() -> runQueue(t)).isInstanceOf(ArmableImportHook.SimulatedCrash.class);
        hook.disarm(batch);
        UUID job = jobOf(t);
        jdbc.update("UPDATE job SET attempts = max_attempts WHERE id = ?", job);

        expireLeases(t);
        jobRunner.sweepExpiredLeases();

        assertThat(jobRow(job, "status")).isEqualTo("FAILED");
        assertThat(batchRow(batch))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "retries-exhausted")
                .containsEntry("last_committed_row", 100);
        assertThat(count(
                        "SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'IMPORT_FAILED'"
                                + " AND payload ->> 'failureReason' = 'retries-exhausted'",
                        batch))
                .isEqualTo(1);
    }

    @Test
    void aBatchInFlightWithNoLiveJobIsFailedAsOrphanedAndCounted() throws Exception {
        Tenant t = newTenant();
        UUID orphan = uploadOk(t, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");
        UUID healthy = uploadOk(t, "gateway-basic.csv", sample("gateway-basic.csv"), "GATEWAY");
        // The bug the check exists for: a batch whose job vanished (here: marked DONE by hand).
        jdbc.update(
                "UPDATE job SET status = 'DONE' WHERE organization_id = ? AND payload ->> 'batchId' = ?",
                t.org(),
                orphan.toString());
        jdbc.update(
                "UPDATE import_batch SET updated_at = now() - interval '1 hour' WHERE organization_id = ?", t.org());
        double before = orphanCount();

        List<UUID> failed = orphans.check(List.of(t.org()), Duration.ofMinutes(10));

        assertThat(failed).containsExactly(orphan);
        assertThat(batchRow(orphan)).containsEntry("status", "FAILED").containsEntry("failure_reason", "orphaned");
        assertThat(batchRow(healthy)).as("its job is still QUEUED").containsEntry("status", "RECEIVED");
        assertThat(count("SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'IMPORT_FAILED'", orphan))
                .isEqualTo(1);
        assertThat(orphanCount() - before).isEqualTo(1.0);
        assertThat(orphans.check(List.of(t.org()), Duration.ofMinutes(10)))
                .as("idempotent")
                .isEmpty();
    }

    @Test
    void aRecentlyUpdatedBatchIsNeverACandidate() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");
        jdbc.update("UPDATE job SET status = 'DONE' WHERE organization_id = ?", t.org());

        assertThat(orphans.check(List.of(t.org()), Duration.ofMinutes(10))).isEmpty();
        assertThat(batchRow(batch)).containsEntry("status", "RECEIVED");
    }

    private double orphanCount() {
        var counter =
                meters.find("reconflow.jobs.orphaned").tag("type", "IMPORT").counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID jobOf(Tenant t) {
        return jdbc.queryForObject("SELECT id FROM job WHERE organization_id = ?", UUID.class, t.org());
    }

    private Object jobRow(UUID job, String column) {
        return jdbc.queryForMap("SELECT * FROM job WHERE id = ?", job).get(column);
    }

    private void expireLeases(Tenant t) {
        jdbc.update("UPDATE job SET lease_until = now() - interval '1 second' WHERE organization_id = ?", t.org());
    }
}
