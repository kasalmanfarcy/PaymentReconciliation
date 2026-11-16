package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.jobs.Job;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M5.25: a worker killed anywhere in an import, then resumed, ends in exactly the state of a clean
 * import, with no duplicate records. Chunks are 100 rows (test profile); the file is 1,000 rows with
 * every 7th invalid. The crash is an Error thrown by the test hook, which the queue doesn't catch,
 * so the job is left RUNNING with its lease, exactly like a killed JVM. The explicit-retry variant
 * (permanent failure after two chunks, then POST /retry) is in ImportRetryIT.
 */
class CrashResumeIT extends AbstractImportIT {

    @Autowired
    ArmableImportHook hook;

    @ParameterizedTest(name = "crash after row {0}")
    @ValueSource(ints = {350, 300, 301, 1000})
    void aCrashedImportResumesFromItsCheckpointToTheCleanResult(int crashAfter) throws Exception {
        byte[] file = ImportRetryIT.thousandRows();
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", file, "INTERNAL_LEDGER");
        hook.crashAfterRow(batch, crashAfter);

        assertThatThrownBy(() -> runQueue(t)).isInstanceOf(ArmableImportHook.SimulatedCrash.class);

        // Row N's chunk was being built when the worker died: only full chunks before it are in.
        int checkpoint = ((crashAfter - 1) / 100) * 100;
        assertThat(batchRow(batch))
                .containsEntry("status", "PROCESSING")
                .containsEntry("last_committed_row", checkpoint)
                .containsEntry("total_rows", checkpoint);
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isEqualTo(checkpoint - checkpoint / 7);

        hook.disarm(batch);
        recoverAndRun(t);

        assertCleanResult(batch, file);
    }

    @Test
    void aCrashInsideTheFinishingTransactionIsRedone() throws Exception {
        byte[] file = ImportRetryIT.thousandRows();
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", file, "INTERNAL_LEDGER");
        hook.crashBeforeFinishCommit(batch);

        assertThatThrownBy(() -> runQueue(t)).isInstanceOf(ArmableImportHook.SimulatedCrash.class);
        assertThat(batchRow(batch)).containsEntry("status", "PROCESSING").containsEntry("last_committed_row", 1000);

        hook.disarm(batch);
        recoverAndRun(t);

        assertCleanResult(batch, file);
    }

    @Test
    void aZombieWhoseLeaseWasTakenCommitsNothingAndTheSuccessorFinishes() throws Exception {
        byte[] file = ImportRetryIT.thousandRows();
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", file, "INTERNAL_LEDGER");
        hook.crashAfterRow(batch, 150); // worker A stalls (GC pause, partition) after one chunk
        Job zombie = jobRunner.claim(jobRunner.workerId("A"), List.of(t.org())).orElseThrow();
        assertThatThrownBy(() -> jobRunner.execute(zombie)).isInstanceOf(ArmableImportHook.SimulatedCrash.class);
        hook.disarm(batch);

        // A's lease expires; the sweeper re-queues the job and worker B claims it.
        expireLeases(t);
        jobRunner.sweepExpiredLeases();
        Job successor =
                jobRunner.claim(jobRunner.workerId("B"), List.of(t.org())).orElseThrow();

        // A wakes up and carries on: its first fenced transaction fails, nothing is written.
        jobRunner.execute(zombie);
        assertThat(batchRow(batch)).containsEntry("last_committed_row", 100);
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isEqualTo(100 - 100 / 7);

        jobRunner.execute(successor);

        assertCleanResult(batch, file);
    }

    private void recoverAndRun(Tenant t) {
        expireLeases(t);
        jobRunner.sweepExpiredLeases();
        runQueue(t);
    }

    private void expireLeases(Tenant t) {
        jdbc.update("UPDATE job SET lease_until = now() - interval '1 second' WHERE organization_id = ?", t.org());
    }

    /** Same counters, same records, no source row twice, one completion event: as if nothing happened. */
    private void assertCleanResult(UUID batch, byte[] file) throws Exception {
        Tenant clean = newTenant();
        UUID reference = uploadOk(clean, "rows.csv", file, "INTERNAL_LEDGER");
        runQueue(clean);

        Map<String, Object> resumed = batchRow(batch);
        Map<String, Object> expected = batchRow(reference);
        for (String column : List.of("status", "total_rows", "valid_rows", "error_rows", "last_committed_row")) {
            assertThat(resumed.get(column)).as(column).isEqualTo(expected.get(column));
        }
        assertThat(rows(batch)).isEqualTo(rows(reference));
        assertThat(errors(batch)).isEqualTo(errors(reference));
        assertThat(jdbc.queryForList(
                        "SELECT source_row_number FROM transaction_record WHERE batch_id = ?"
                                + " GROUP BY 1 HAVING count(*) > 1",
                        Integer.class,
                        batch))
                .isEmpty();
        assertThat(count("SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'IMPORT_COMPLETED'", batch))
                .isEqualTo(1);
    }

    private List<Map<String, Object>> rows(UUID batch) {
        return jdbc.queryForList(
                "SELECT source_row_number, normalized_reference, amount, currency, transaction_date, is_duplicate_in_source"
                        + " FROM transaction_record WHERE batch_id = ? ORDER BY source_row_number",
                batch);
    }

    private List<Map<String, Object>> errors(UUID batch) {
        return jdbc.queryForList(
                "SELECT row_number, column_name, error_code FROM import_row_error WHERE batch_id = ?"
                        + " ORDER BY row_number, column_name",
                batch);
    }
}
