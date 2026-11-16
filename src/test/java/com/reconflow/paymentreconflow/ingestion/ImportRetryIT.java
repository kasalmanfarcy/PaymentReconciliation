package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** M5.20: an explicit, audited reprocess of a FAILED batch, from a clean slate. */
class ImportRetryIT extends AbstractImportIT {

    @Autowired
    ArmableImportHook hook;

    @Test
    void aBatchThatFailedAfterTwoChunksIsRetriedToTheSameResultAsACleanImport() throws Exception {
        byte[] file = thousandRows();
        Tenant clean = newTenant();
        UUID cleanBatch = uploadOk(clean, "rows.csv", file, "INTERNAL_LEDGER");
        runQueue(clean);

        Tenant t = newTenant();
        UUID batch = uploadOk(t, "rows.csv", file, "INTERNAL_LEDGER");
        hook.failAfterRow(batch, 250); // a bug at row 250: permanent, after two committed chunks
        runQueue(t);
        hook.disarm(batch);
        assertThat(batchRow(batch))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "internal-error")
                .containsEntry("last_committed_row", 200);
        // 200 rows committed; every 7th (28 of them) was invalid.
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isEqualTo(172);

        MockHttpServletResponse res = retry(t, batch, "{\"reason\":\"Fixed the parser bug\"}");

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        assertThat(res.getHeader("Location")).isEqualTo("/api/v1/imports/" + batch);
        assertThat(json(res))
                .containsEntry("status", "RECEIVED")
                .containsEntry("totalRows", 0)
                .containsEntry("failureReason", null)
                .containsEntry("startedAt", null);
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isZero();
        assertThat(jdbc.queryForList(
                        "SELECT status FROM job WHERE organization_id = ? ORDER BY created_at", String.class, t.org()))
                .containsExactly("FAILED", "QUEUED"); // the old job stays for history
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_id, payload->>'reason' AS reason, payload->>'previousFailureReason' AS previous"
                        + " FROM audit_event WHERE entity_id = ? AND action = 'IMPORT_RETRY_REQUESTED'",
                batch);
        assertThat(audit)
                .containsEntry("actor_id", t.uploader())
                .containsEntry("reason", "Fixed the parser bug")
                .containsEntry("previous", "internal-error");

        runQueue(t);

        Map<String, Object> retried = batchRow(batch);
        Map<String, Object> reference = batchRow(cleanBatch);
        for (String column : List.of("status", "total_rows", "valid_rows", "error_rows", "last_committed_row")) {
            assertThat(retried.get(column)).as(column).isEqualTo(reference.get(column));
        }
        assertThat(rowsOf(batch)).isEqualTo(rowsOf(cleanBatch));
    }

    @Test
    void onlyAFailedBatchCanBeRetried() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");
        runQueue(t);

        MockHttpServletResponse res = retry(t, batch, "{\"reason\":\"again\"}");

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json(res))
                .containsEntry("code", "invalid-transition")
                .containsEntry("type", "https://reconflow.dev/problems/invalid-transition")
                .containsEntry("currentStatus", "COMPLETED");
    }

    @Test
    void aRetryNeedsAReason() throws Exception {
        Tenant t = newTenant();
        UUID batch = data().newImportBatch(t.org(), t.uploader(), "GATEWAY", "FAILED");

        assertThat(retry(t, batch, "{\"reason\":\"  \"}").getStatus()).isEqualTo(400);
        assertThat(retry(t, batch, "{}").getStatus()).isEqualTo(400);
        assertThat(retry(t, batch, "").getStatus()).isEqualTo(400);
        assertThat(batchRow(batch)).containsEntry("status", "FAILED");
    }

    @Test
    void twoConcurrentRetriesAreSerializedAndOnlyOneWins() throws Exception {
        Tenant t = newTenant();
        UUID batch = data().newImportBatch(t.org(), t.uploader(), "GATEWAY", "FAILED");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return retry(t, batch, "{\"reason\":\"click\"}").getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
            assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", t.org()))
                    .isEqualTo(1);
            assertThat(count(
                            "SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'IMPORT_RETRY_REQUESTED'",
                            batch))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private MockHttpServletResponse retry(Tenant t, UUID batch, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/imports/" + batch + "/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(as(t.uploader()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse();
    }

    static byte[] thousandRows() {
        StringBuilder csv = new StringBuilder("reference,amount,currency,status,transaction_date\n");
        for (int i = 1; i <= 1000; i++) {
            // Every 7th row is invalid, so errors are part of what must match too.
            String amount = i % 7 == 0 ? "abc" : i + ".10";
            csv.append("PAY-")
                    .append(i)
                    .append(',')
                    .append(amount)
                    .append(",AED,SUCCESS,2026-10-0")
                    .append(1 + i % 9)
                    .append('\n');
        }
        return utf8(csv.toString());
    }

    /** Everything about a batch's records except ids and the batch itself. */
    List<Map<String, Object>> rowsOf(UUID batch) {
        return jdbc.queryForList(
                "SELECT source_row_number, normalized_reference, amount, currency, transaction_date, is_duplicate_in_source"
                        + " FROM transaction_record WHERE batch_id = ? ORDER BY source_row_number",
                batch);
    }
}
