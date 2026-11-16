package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M5.24: bad rows never stop an import. Valid rows are stored, every bad row is reported with its
 * row, column and reason, and the batch says which of the three outcomes it reached.
 */
class MalformedRowsIT extends AbstractImportIT {

    static final String HEADER = "reference,amount,currency,status,transaction_date\n";

    @Test
    @SuppressWarnings("unchecked")
    void twoBadRowsOfTenEndCompletedWithErrorsWithEveryProblemReported() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 10; i++) {
            csv.append(
                    switch (i) {
                        case 3 -> "PAY-3,12.3.4,AED,SUCCESS,2026-10-01\n"; // one problem
                        case 7 -> "PAY-7,7.00,,SUCCESS,2026/10/01\n"; // two problems
                        default -> "PAY-" + i + "," + i + ".00,AED,SUCCESS,2026-10-01\n";
                    });
        }
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ten.csv", utf8(csv.toString()), "INTERNAL_LEDGER");

        runQueue(t);

        assertThat(batchRow(batch))
                .containsEntry("status", "COMPLETED_WITH_ERRORS")
                .containsEntry("total_rows", 10)
                .containsEntry("valid_rows", 8)
                .containsEntry("error_rows", 2);
        assertThat(jdbc.queryForList(
                        "SELECT source_row_number FROM transaction_record WHERE batch_id = ? ORDER BY 1",
                        Integer.class,
                        batch))
                .containsExactly(1, 2, 4, 5, 6, 8, 9, 10);

        Map<String, Object> page = json(mockMvc.perform(get("/api/v1/imports/" + batch + "/errors")
                        .with(as(t.uploader()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse());
        List<Map<String, Object>> errors = (List<Map<String, Object>>) page.get("content");
        assertThat(errors)
                .extracting(e -> e.get("rowNumber") + ":" + e.get("column") + ":" + e.get("code"))
                .containsExactly(
                        "3:amount:INVALID_AMOUNT",
                        "7:currency:MISSING_REQUIRED_FIELD",
                        "7:transaction_date:INVALID_DATE");
        assertThat(errors.get(1)).containsEntry("rawRow", "PAY-7,7.00,,SUCCESS,2026/10/01");
        assertThat(errors).allSatisfy(e -> assertThat((String) e.get("message")).doesNotContain("Exception"));
    }

    @Test
    void aFileWhoseEveryRowIsInvalidFails() throws Exception {
        // requirements.md §7.5 (the docs win over the plan, which said COMPLETED_WITH_ERRORS).
        Tenant t = newTenant();
        UUID batch = uploadOk(
                t,
                "bad.csv",
                utf8(HEADER + "PAY-1,-1,AED,SUCCESS,2026-10-01\nPAY-2,2,XYZ,SUCCESS,2026-10-01\n"),
                "INTERNAL_LEDGER");

        runQueue(t);

        assertThat(batchRow(batch))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "no-valid-rows")
                .containsEntry("valid_rows", 0)
                .containsEntry("error_rows", 2);
        assertThat(count("SELECT count(*) FROM import_row_error WHERE batch_id = ?", batch))
                .isEqualTo(2);
    }

    @Test
    void storedErrorsAreCappedButEveryBadRowIsCounted() throws Exception {
        // 6,000 rows with two errors each would be 12,000 error rows; the cap stores 10,000.
        StringBuilder csv = new StringBuilder(HEADER).append("PAY-OK,1.00,AED,SUCCESS,2026-10-01\n");
        for (int i = 1; i <= 6000; i++) {
            csv.append("PAY-").append(i).append(",abc,XYZ,SUCCESS,2026-10-01\n");
        }
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "many.csv", utf8(csv.toString()), "INTERNAL_LEDGER");

        runQueue(t);

        assertThat(batchRow(batch))
                .containsEntry("status", "COMPLETED_WITH_ERRORS")
                .containsEntry("valid_rows", 1)
                .containsEntry("error_rows", 6000);
        assertThat(count("SELECT count(*) FROM import_row_error WHERE batch_id = ?", batch))
                .isEqualTo(10_000);
        assertThat(json(mockMvc.perform(get("/api/v1/imports/" + batch)
                                .with(as(t.uploader()))
                                .with(org(t.org())))
                        .andReturn()
                        .getResponse()))
                .containsEntry("errorRows", 6000)
                .containsEntry("storedErrors", 10_000);
    }

    @Test
    void anInvalidByteInTheBodyFailsTheBatchNamingTheRow() throws Exception {
        byte[] head = utf8(HEADER + "PAY-1,1.00,AED,SUCCESS,2026-10-01\nPAY-");
        byte[] tail = utf8(",2.00,AED,SUCCESS,2026-10-01\n");
        byte[] file = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, file, 0, head.length);
        file[head.length] = (byte) 0xFF; // never valid in UTF-8
        System.arraycopy(tail, 0, file, head.length + 1, tail.length);
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "latin1.csv", file, "INTERNAL_LEDGER"); // the header is fine

        runQueue(t);

        assertThat(batchRow(batch))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "invalid-encoding at row 2");
    }
}
