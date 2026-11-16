package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M5.16: each import transition writes its audit event in the same transaction as the change. */
class ImportAuditIT extends AbstractImportIT {

    @Autowired
    ArmableImportHook hook;

    @Test
    void aCompletedImportIsAuditedAsASystemEventWithCountsAndTheJob() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-malformed.csv", sample("ledger-malformed.csv"), "INTERNAL_LEDGER");

        runQueue(t);

        List<Map<String, Object>> events = events(batch);
        assertThat(events).extracting(e -> e.get("action")).containsExactly("IMPORT_RECEIVED", "IMPORT_COMPLETED");
        Map<String, Object> completed = events.get(1);
        assertThat(completed.get("actor_id")).isNull();
        assertThat(completed.get("payload").toString())
                .contains("\"status\": \"COMPLETED_WITH_ERRORS\"")
                .contains("\"totalRows\": 9")
                .contains("\"validRows\": 1")
                .contains("\"errorRows\": 8")
                .contains("\"durationMs\"")
                .contains("\"jobId\"")
                .doesNotContain("PAY-4"); // no references or row contents in audit payloads
    }

    @Test
    void aFailedImportIsAuditedWithItsReason() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(
                t,
                "bad.csv",
                utf8("reference,amount,currency,status,transaction_date\nPAY-1,abc,AED,SUCCESS,2026-10-01\n"),
                "INTERNAL_LEDGER");

        runQueue(t);

        assertThat(batchRow(batch)).containsEntry("status", "FAILED").containsEntry("failure_reason", "no-valid-rows");
        Map<String, Object> failed = events(batch).get(1);
        assertThat(failed).containsEntry("action", "IMPORT_FAILED");
        assertThat(failed.get("payload").toString())
                .contains("\"failureReason\": \"no-valid-rows\"")
                .contains("\"attempts\": 1")
                .contains("\"lastCommittedRow\": 1");
    }

    @Test
    void aFinishingTransactionThatRollsBackLeavesNoAuditRowAndIsRedoneOnce() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "gateway-duplicates.csv", sample("gateway-duplicates.csv"), "GATEWAY");
        hook.crashBeforeFinishCommit(batch);

        assertThatThrownBy(() -> runQueue(t)).isInstanceOf(ArmableImportHook.SimulatedCrash.class);

        // The flags, the status and the audit row all rolled back together.
        assertThat(batchRow(batch)).containsEntry("status", "PROCESSING");
        assertThat(events(batch)).extracting(e -> e.get("action")).containsExactly("IMPORT_RECEIVED");
        assertThat(count(
                        "SELECT count(*) FROM transaction_record WHERE batch_id = ? AND is_duplicate_in_source", batch))
                .isZero();

        hook.disarm(batch);
        jdbc.update("UPDATE job SET lease_until = now() - interval '1 second' WHERE organization_id = ?", t.org());
        jobRunner.sweepExpiredLeases();
        runQueue(t);

        assertThat(batchRow(batch)).containsEntry("status", "COMPLETED");
        assertThat(events(batch))
                .extracting(e -> e.get("action"))
                .containsExactly("IMPORT_RECEIVED", "IMPORT_COMPLETED");
        assertThat(count(
                        "SELECT count(*) FROM transaction_record WHERE batch_id = ? AND is_duplicate_in_source", batch))
                .isEqualTo(3);
    }

    private List<Map<String, Object>> events(UUID batch) {
        return jdbc.queryForList(
                "SELECT action, actor_id, payload::text AS payload FROM audit_event WHERE entity_id = ?"
                        + " ORDER BY created_at, id",
                batch);
    }
}
