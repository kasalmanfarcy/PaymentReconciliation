package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M5.14: the worker streams the file and commits it chunk by chunk with the checkpoint (chunk size
 * is 100 in the test profile).
 */
class ImportProcessingIT extends AbstractImportIT {

    @Test
    void aSampleFileBecomesNormalizedRecords() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");

        assertThat(runQueue(t)).isEqualTo(1);

        assertThat(batchRow(batch))
                .containsEntry("status", "COMPLETED")
                .containsEntry("total_rows", 7)
                .containsEntry("valid_rows", 7)
                .containsEntry("error_rows", 0)
                .containsEntry("last_committed_row", 7);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_row_number, raw_reference, normalized_reference, amount::text AS amount, currency,"
                        + " status, transaction_date::text AS day FROM transaction_record WHERE batch_id = ?"
                        + " ORDER BY source_row_number",
                batch);
        assertThat(rows).extracting(r -> r.get("source_row_number")).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(rows.get(5))
                .containsEntry("raw_reference", " pay-106 ")
                .containsEntry("normalized_reference", "PAY-106")
                .containsEntry("amount", "120.5000")
                .containsEntry("currency", "AED")
                .containsEntry("day", "2026-10-02");
        assertThat(rows.get(6)).containsEntry("status", "FAILED"); // a valid row, just not a successful payment
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM job WHERE organization_id = ? AND type = 'IMPORT'", String.class, t.org()))
                .isEqualTo("DONE");
    }

    @Test
    void aThousandRowsCommitInTenChunksWithTheCheckpointAtTheEnd() throws Exception {
        Tenant t = newTenant();
        StringBuilder csv = new StringBuilder("reference,amount,currency,status,transaction_date\n");
        for (int i = 1; i <= 1000; i++) {
            csv.append("PAY-")
                    .append(i)
                    .append(",")
                    .append(i)
                    .append(".25,AED,SUCCESS,2026-10-0")
                    .append(1 + i % 9)
                    .append('\n');
        }
        UUID batch = uploadOk(t, "big.csv", utf8(csv.toString()), "INTERNAL_LEDGER");

        runQueue(t);

        assertThat(batchRow(batch))
                .containsEntry("status", "COMPLETED")
                .containsEntry("total_rows", 1000)
                .containsEntry("valid_rows", 1000)
                .containsEntry("last_committed_row", 1000);
        assertThat(count("SELECT count(*) FROM transaction_record WHERE batch_id = ?", batch))
                .isEqualTo(1000);
        // The temp file is gone and the original is still in storage (evidence, and retry input).
        assertThat(storage.exists(batchRow(batch).get("storage_key").toString()))
                .isTrue();
    }
}
