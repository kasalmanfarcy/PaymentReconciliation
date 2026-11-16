package com.reconflow.paymentreconflow.reporting;

import static com.reconflow.paymentreconflow.reporting.ReconciliationReportIT.list;
import static com.reconflow.paymentreconflow.reporting.ReconciliationReportIT.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M8.2: {@code GET /api/v1/reports/import-quality}. Per batch on the documented malformed sample and
 * on a row with two bad columns (two errors, one error row); over time on hand-built batches whose
 * buckets can be worked out by hand, including the UTC-midnight boundary.
 */
class ImportQualityReportIT extends AbstractIntegrationTest {

    static final String REPORT = "/api/v1/reports/import-quality";

    @Autowired
    JobRunner jobRunner;

    @Test
    void theMalformedSampleByBatch() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID batch =
                upload(f, "ledger-malformed.csv", Files.readAllBytes(Path.of("docs/samples/ledger-malformed.csv")));

        Map<String, Object> batchReport = map(report(f, "?batchId=" + batch), "batch");

        // docs/samples/expected-imports.yaml: 9 rows, 1 valid, 8 errors on 8 rows, one per code.
        assertThat(batchReport)
                .containsEntry("id", batch.toString())
                .containsEntry("status", "COMPLETED_WITH_ERRORS")
                .containsEntry("sourceType", "INTERNAL_LEDGER")
                .containsEntry("totalRows", 9)
                .containsEntry("validRows", 1)
                .containsEntry("errorRows", 8)
                .containsEntry("stored", Map.of("errors", 8, "errorRows", 8));
        assertThat(list(batchReport, "byErrorCode"))
                .extracting(r -> r.get("errorCode") + " " + r.get("errors") + "/" + r.get("errorRows"))
                .containsExactlyInAnyOrder(
                        "INVALID_AMOUNT 1/1",
                        "AMOUNT_SCALE_EXCEEDED 1/1",
                        "AMOUNT_NOT_POSITIVE 1/1",
                        "INVALID_CURRENCY 1/1",
                        "INVALID_DATE 1/1",
                        "UNKNOWN_STATUS 1/1",
                        "MISSING_REQUIRED_FIELD 1/1",
                        "MALFORMED_ROW 1/1");
        assertThat(list(batchReport, "byErrorCodeAndColumn"))
                .extracting(r -> r.get("errorCode") + "@" + r.get("column"))
                .contains("INVALID_CURRENCY@currency", "MISSING_REQUIRED_FIELD@reference", "MALFORMED_ROW@null")
                .hasSize(8);
    }

    @Test
    void aRowWithTwoBadColumnsIsTwoErrorsButOneErrorRow() throws Exception {
        var f = new ReportFixture(jdbc);
        String csv = "reference,amount,currency,status,transaction_date\n"
                + "PAY-1,10.00,AED,SUCCESS,2026-10-05\n"
                + "PAY-2,abc,XYZ,SUCCESS,2026-10-05\n"; // bad amount AND bad currency
        UUID batch = upload(f, "two-bad-columns.csv", csv.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> batchReport = map(report(f, "?batchId=" + batch), "batch");

        assertThat(batchReport)
                .containsEntry("errorRows", 1)
                .containsEntry("stored", Map.of("errors", 2, "errorRows", 1));
        assertThat(list(batchReport, "byErrorCode"))
                .extracting(r -> r.get("errorCode") + " " + r.get("errors") + "/" + r.get("errorRows"))
                .containsExactlyInAnyOrder("INVALID_AMOUNT 1/1", "INVALID_CURRENCY 1/1");
    }

    /**
     * Three batches by hand: a ledger batch at 23:59:59Z on 1 October (two errors on one row, one on
     * another), a gateway batch one second later, on 2 October UTC (one error), and a clean ledger
     * batch on 2 October. Plus one batch outside the range, which must not count.
     */
    @Test
    void overTimeBucketsByUploadDayInUtc() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID late = batch(f, "INTERNAL_LEDGER", "2026-10-01T23:59:59Z", 10, 2);
        error(f, late, 3, "amount", "INVALID_AMOUNT");
        error(f, late, 3, "currency", "INVALID_CURRENCY");
        error(f, late, 7, "amount", "INVALID_AMOUNT");
        UUID early = batch(f, "GATEWAY", "2026-10-02T00:00:00Z", 5, 1);
        error(f, early, 2, "status", "UNKNOWN_STATUS");
        batch(f, "INTERNAL_LEDGER", "2026-10-02T12:00:00Z", 4, 0);
        UUID outside = batch(f, "INTERNAL_LEDGER", "2026-10-03T00:00:00Z", 100, 1);
        error(f, outside, 1, "amount", "INVALID_AMOUNT");

        Map<String, Object> byDay = report(f, "?from=2026-10-01&to=2026-10-02");

        assertThat(byDay.get("period"))
                .isEqualTo(ReconciliationReportIT.mapOf(
                        "from", "2026-10-01", "to", "2026-10-02", "granularity", "DAY", "sourceType", null));
        assertThat(list(byDay, "buckets"))
                .extracting(b -> b.get("bucket") + " batches=" + b.get("batches") + " rows=" + b.get("totalRows")
                        + " batchErrorRows=" + b.get("errorRows") + " stored=" + b.get("stored") + " "
                        + b.get("byErrorCode"))
                .containsExactly(
                        "2026-10-01 batches=1 rows=10 batchErrorRows=2 stored={errors=3, errorRows=2} "
                                + "[{errorCode=INVALID_AMOUNT, errors=2, errorRows=2},"
                                + " {errorCode=INVALID_CURRENCY, errors=1, errorRows=1}]",
                        "2026-10-02 batches=2 rows=9 batchErrorRows=1 stored={errors=1, errorRows=1} "
                                + "[{errorCode=UNKNOWN_STATUS, errors=1, errorRows=1}]");

        assertThat(list(report(f, "?from=2026-10-01&to=2026-10-02&granularity=month"), "buckets"))
                .extracting(b -> b.get("bucket") + " " + b.get("batches") + " " + b.get("stored"))
                .containsExactly("2026-10-01 3 {errors=4, errorRows=3}");

        assertThat(list(report(f, "?from=2026-10-01&to=2026-10-02&sourceType=GATEWAY"), "buckets"))
                .extracting(b -> b.get("bucket") + " " + b.get("batches") + " " + b.get("stored"))
                .containsExactly("2026-10-02 1 {errors=1, errorRows=1}");

        // Another organization's identical batches don't show: a fresh organization sees nothing.
        var other = new ReportFixture(jdbc);
        assertThat(list(report(other, "?from=2026-10-01&to=2026-10-02"), "buckets"))
                .isEmpty();
    }

    @Test
    void anUnknownBatchIs404() throws Exception {
        var f = new ReportFixture(jdbc);
        assertThat(call(f, "?batchId=" + UUID.randomUUID()).getStatus()).isEqualTo(404);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "no parameters        | ''                                                        | batchId",
                "both modes           | ?batchId=0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e&from=2026-10-01 | batchId",
                "no end               | ?from=2026-10-01                                          | to",
                "reversed range       | ?from=2026-10-02&to=2026-10-01                            | to",
                "unknown granularity  | ?from=2026-10-01&to=2026-10-02&granularity=hour           | granularity",
                "unknown source       | ?from=2026-10-01&to=2026-10-02&sourceType=BANK            | sourceType",
            })
    void badParametersAre400(String name, String query, String field) throws Exception {
        MockHttpServletResponse res = call(new ReportFixture(jdbc), query);

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("\"field\":\"" + field + "\"");
    }

    private UUID batch(ReportFixture f, String source, String createdAt, int total, int errorRows) {
        UUID id = new TestData(jdbc).newImportBatch(f.org, f.admin.id(), source, "COMPLETED_WITH_ERRORS");
        jdbc.update(
                "UPDATE import_batch SET created_at = ?, total_rows = ?, valid_rows = ?, error_rows = ? WHERE id = ?",
                OffsetDateTime.parse(createdAt),
                total,
                total - errorRows,
                errorRows,
                id);
        return id;
    }

    private void error(ReportFixture f, UUID batch, int row, String column, String code) {
        jdbc.update(
                "INSERT INTO import_row_error (id, organization_id, batch_id, row_number, column_name, error_code,"
                        + " message) VALUES (?, ?, ?, ?, ?, ?, 'bad')",
                Ids.newId(),
                f.org,
                batch,
                row,
                column,
                code);
    }

    private UUID upload(ReportFixture f, String name, byte[] content) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", name, "text/csv", content))
                        .param("sourceType", "INTERNAL_LEDGER")
                        .with(as(f.admin.id()))
                        .with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        jobRunner.runAllQueued(List.of(f.org));
        return UUID.fromString(jsonMapper
                .readValue(res.getContentAsString(), Map.class)
                .get("id")
                .toString());
    }

    private MockHttpServletResponse call(ReportFixture f, String query) throws Exception {
        return mockMvc.perform(get(REPORT + query).with(as(f.viewer.id())).with(org(f.org)))
                .andReturn()
                .getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> report(ReportFixture f, String query) throws Exception {
        MockHttpServletResponse res = call(f, query);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }
}
