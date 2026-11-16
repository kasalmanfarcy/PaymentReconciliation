package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.support.ScriptedFaultInjector;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * M9.3: a chunk whose transaction fails transiently is retried in-process, as a whole new
 * transaction, and committed exactly once; a permanent failure is never retried. Each test compares
 * the batch with a clean import of the same 1,000-row file (100-row chunks in the test profile).
 */
class ImportTransientRetryIT extends AbstractImportIT {

    static final String CHUNK = FaultPoints.IMPORT_BEFORE_CHUNK_COMMIT;

    @Autowired
    ScriptedFaultInjector faults;

    @Autowired
    MeterRegistry meters;

    @AfterEach
    void clear() {
        faults.clear();
    }

    @Test
    void aLockTimeoutInAChunkIsRetriedInProcessAndTheChunkCommitsOnce() throws Exception {
        double retriesBefore = retries();
        faults.onHit(CHUNK, 3, () -> {
            throw new CannotAcquireLockException("simulated lock_not_available (55P03)");
        });

        UUID batch = importThousandRows();

        assertThat(faults.hits(CHUNK)).as("10 chunks, one of them twice").isEqualTo(11);
        assertThat(retries() - retriesBefore).isEqualTo(1.0);
        assertThat(jobAttempts(batch))
                .as("fixed in-process, not by the job queue")
                .isEqualTo(1);
        assertSameAsCleanImport(batch);
    }

    @Test
    void aDroppedDatabaseConnectionMidChunkIsRetriedOnAFreshConnection() throws Exception {
        // The real thing: the server kills this transaction's backend (SQLState 57P01), the pool
        // discards the dead connection, and the retry runs on a new one.
        faults.onHit(CHUNK, 4, () -> jdbc.execute("SELECT pg_terminate_backend(pg_backend_pid())"));

        UUID batch = importThousandRows();

        assertThat(jobAttempts(batch)).isEqualTo(1);
        assertSameAsCleanImport(batch);
    }

    @Test
    void aCommitThatLandedBeforeItsConnectionFailedIsNotAppliedTwiceByTheRetry() throws Exception {
        // The nasty case of §M9.3: COMMIT succeeds, then the client sees an error, so it can't know.
        // Simulated by failing *after* commit; the retry must recognise the chunk is already in.
        faults.onHit(
                CHUNK,
                5,
                () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new CannotAcquireLockException("connection lost after COMMIT was sent");
                    }
                }));

        UUID batch = importThousandRows();

        assertThat(faults.hits(CHUNK))
                .as("the retry stops at the checkpoint check, before the hook")
                .isEqualTo(10);
        assertSameAsCleanImport(batch);
    }

    @Test
    void aConstraintViolationIsPermanentAndNeverRetried() throws Exception {
        faults.fromHit(CHUNK, 2, () -> {
            throw new DataIntegrityViolationException("simulated 23505");
        });

        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", ImportRetryIT.thousandRows(), "INTERNAL_LEDGER");
        runQueue(t);

        assertThat(faults.hits(CHUNK))
                .as("called once for chunk 2, never again")
                .isEqualTo(2);
        assertThat(batchRow(batch))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "internal-error")
                .containsEntry("last_committed_row", 100);
        assertThat(jobAttempts(batch)).isEqualTo(1);
    }

    private UUID importThousandRows() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", ImportRetryIT.thousandRows(), "INTERNAL_LEDGER");
        runQueue(t);
        return batch;
    }

    private void assertSameAsCleanImport(UUID batch) throws Exception {
        faults.clear();
        Tenant clean = newTenant();
        UUID reference = uploadOk(clean, "rows.csv", ImportRetryIT.thousandRows(), "INTERNAL_LEDGER");
        runQueue(clean);
        Map<String, Object> got = batchRow(batch);
        Map<String, Object> expected = batchRow(reference);
        for (String column : List.of("status", "total_rows", "valid_rows", "error_rows", "last_committed_row")) {
            assertThat(got.get(column)).as(column).isEqualTo(expected.get(column));
        }
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isEqualTo(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", reference));
        assertThat(count("SELECT count(*) FROM import_row_error WHERE batch_id = ?", batch))
                .isEqualTo(count("SELECT count(*) FROM import_row_error WHERE batch_id = ?", reference));
        assertThat(count("SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'IMPORT_COMPLETED'", batch))
                .isEqualTo(1);
    }

    private int jobAttempts(UUID batch) {
        return jdbc.queryForObject(
                "SELECT attempts FROM job WHERE payload ->> 'batchId' = ?", Integer.class, batch.toString());
    }

    private double retries() {
        var c = meters.find("reconflow.db.retries")
                .tag("operation", "import.chunk")
                .counter();
        return c == null ? 0 : c.count();
    }
}
