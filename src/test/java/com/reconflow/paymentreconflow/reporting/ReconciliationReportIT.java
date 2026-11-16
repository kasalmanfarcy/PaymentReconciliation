package com.reconflow.paymentreconflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M8.1: {@code GET /api/v1/reports/reconciliation}. Run mode on the documented sample (one call
 * answers "which match, which don't, why, who resolved them"), the 404/409/400 answers, and
 * date-range mode over two overlapping runs, which must count every day exactly once.
 */
class ReconciliationReportIT extends AbstractIntegrationTest {

    static final String REPORT = "/api/v1/reports/reconciliation";
    static final LocalDate OCT = LocalDate.of(2026, 10, 1);

    @Autowired
    JobRunner jobRunner;

    @Test
    void oneCallAnswersTheFourQuestionsForARun() throws Exception {
        var f = new ReportFixture(jdbc);
        importSample(f, "ledger-basic.csv", "INTERNAL_LEDGER");
        importSample(f, "gateway-basic.csv", "GATEWAY");
        UUID run = requestRun(f, "2026-10-01", "2026-10-02");
        UUID mismatch = jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND type = 'AMOUNT_MISMATCH'",
                UUID.class,
                f.org);
        MockHttpServletResponse resolved = mockMvc.perform(post("/api/v1/exceptions/" + mismatch + "/resolve")
                        .with(as(f.investigator.id()))
                        .with(org(f.org))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionCode\":\"GATEWAY_FEE\"}"))
                .andReturn()
                .getResponse();
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);

        Map<String, Object> report = report(f, "?runId=" + run);

        // docs/samples/README.md §1, by hand: 3 matched (1000 + 300 + 120.5), PAY-102 missing in the
        // gateway, PAY-108 missing in the ledger, PAY-103 short by 50, PAY-104 duplicated, PAY-107
        // excluded (FAILED).
        assertThat(report)
                .containsEntry("currency", "AED")
                .containsEntry(
                        "scope",
                        Map.of("mode", "RUN", "runId", run.toString(), "from", "2026-10-01", "to", "2026-10-02"))
                .containsEntry("matched", Map.of("count", 3, "amount", "1420.5000"))
                .containsEntry("unmatchedLedger", Map.of("count", 1, "amount", "500.0000"))
                .containsEntry("unmatchedGateway", Map.of("count", 1, "amount", "200.0000"))
                .containsEntry("mismatches", Map.of("count", 1, "netDifference", "-50.0000"))
                .containsEntry("duplicates", Map.of("count", 1))
                .containsEntry("currencyMismatches", Map.of("count", 0))
                .containsEntry("excluded", Map.of("count", 1))
                .containsEntry("coverage", Map.of("requestedDays", 2, "coveredDays", 2, "missingDays", List.of()));
        // Why: by type and reason, with the status split.
        assertThat(list(report, "exceptionsByReason"))
                .extracting(r -> r.get("type") + "/" + r.get("reasonCode") + " " + r.get("count") + " open="
                        + r.get("open") + " resolved=" + r.get("resolved") + " amount=" + r.get("amount")
                        + " diff=" + r.get("amountDifference"))
                .containsExactlyInAnyOrder(
                        "AMOUNT_MISMATCH/null 1 open=0 resolved=1 amount=750.0000 diff=-50.0000",
                        "DUPLICATE_IN_SOURCE/SAME_AMOUNT 1 open=1 resolved=0 amount=250.0000 diff=0.0000",
                        "MISSING_IN_GATEWAY/null 1 open=1 resolved=0 amount=500.0000 diff=0.0000",
                        "MISSING_IN_LEDGER/null 1 open=1 resolved=0 amount=200.0000 diff=0.0000");
        // Who: the resolver, by name.
        String name = jdbc.queryForObject(
                "SELECT display_name FROM app_user WHERE id = ?", String.class, f.investigator.id());
        assertThat(report.get("resolution"))
                .isEqualTo(Map.of(
                        "open",
                        3,
                        "inReview",
                        0,
                        "resolved",
                        1,
                        "byResolver",
                        List.of(Map.of("userId", f.investigator.id().toString(), "displayName", name, "resolved", 1))));
        assertThat(list(report, "runsUsed"))
                .singleElement()
                .satisfies(r -> assertThat(r)
                        .containsEntry("runId", run.toString())
                        .containsEntry("days", List.of("2026-10-01", "2026-10-02")));
    }

    @Test
    void anUnknownRunIs404AndAnUnfinishedOneIs409() throws Exception {
        var f = new ReportFixture(jdbc);
        assertThat(call(f, "?runId=" + UUID.randomUUID()).getStatus()).isEqualTo(404);

        UUID pending = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, triggered_by)
                VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-02', 'AED', ?)""", pending, f.org, "client:" + pending, "0".repeat(64), f.admin.id());
        MockHttpServletResponse res = call(f, "?runId=" + pending);

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString())
                .contains("\"code\":\"run-not-completed\"")
                .contains("\"currentStatus\":\"PENDING\"");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "no parameters                 | ''                                                         | runId",
                "both modes                    | ?runId=0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e&currency=AED   | runId",
                "range without a currency      | ?from=2026-10-01&to=2026-10-31                             | currency",
                "range without an end          | ?from=2026-10-01&currency=AED                              | to",
                "reversed range                | ?from=2026-10-31&to=2026-10-01&currency=AED                | to",
                "range over 366 days           | ?from=2025-01-01&to=2026-01-02&currency=AED                | to",
                "not a currency                | ?from=2026-10-01&to=2026-10-31&currency=ABC                | currency",
                "not a date                    | ?from=yesterday&to=2026-10-31&currency=AED                 | ''",
            })
    void badParametersAre400(String name, String query, String field) throws Exception {
        var f = new ReportFixture(jdbc);
        MockHttpServletResponse res = call(f, query);

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("\"code\":\"validation-failed\"");
        if (!field.isEmpty()) {
            assertThat(res.getContentAsString()).contains("\"field\":\"" + field + "\"");
        }
    }

    /**
     * Run 1 covers 1–7 October; then a gateway amount on the 6th changes and the gateway half of a
     * missing payment arrives; run 2 covers 5–10 October. Days 5–7 are in both runs. The range
     * report must take 1–4 from run 1 and 5–10 from run 2, counting each day once.
     */
    @Test
    void aDateRangeCountsEveryDayOnceFromTheLatestRun() throws Exception {
        var f = new ReportFixture(jdbc);
        for (int d = 0; d < 10; d++) {
            f.ledger("M-" + d, "100.00", OCT.plusDays(d));
            f.gateway("M-" + d, "100.00", OCT.plusDays(d));
        }
        f.ledger("P-6", "60.00", OCT.plusDays(5));
        UUID p6Gateway = f.gateway("P-6", "60.00", OCT.plusDays(5));
        f.ledger("Q-6", "30.00", OCT.plusDays(5)); // missing in the gateway, for run 1 only
        UUID run1 = f.run(jobRunner, OCT, OCT.plusDays(6));

        jdbc.update("UPDATE transaction_record SET amount = 65.00 WHERE id = ?", p6Gateway);
        f.gateway("Q-6", "30.00", OCT.plusDays(5));
        UUID run2 = f.run(jobRunner, OCT.plusDays(4), OCT.plusDays(9));

        // Adding up the two runs' totals would count days 5-7 twice: 8 + 7 = 15 matches.
        assertThat(jdbc.queryForObject(
                        "SELECT sum(matched_count) FROM reconciliation_run WHERE id IN (?, ?)", Long.class, run1, run2))
                .isEqualTo(15);

        Map<String, Object> report = report(f, "?from=2026-10-01&to=2026-10-10&currency=AED");

        // 1-4 from run 1: M-0..M-3. 5-10 from run 2: M-4..M-9 and Q-6; P-6 is now a mismatch of +5.
        assertThat(report)
                .containsEntry(
                        "scope", mapOf("mode", "DATE_RANGE", "runId", null, "from", "2026-10-01", "to", "2026-10-10"))
                .containsEntry("matched", Map.of("count", 11, "amount", "1030.0000"))
                .containsEntry("mismatches", Map.of("count", 1, "netDifference", "5.0000"))
                .containsEntry("unmatchedLedger", Map.of("count", 0, "amount", "0.0000"))
                .containsEntry("unmatchedGateway", Map.of("count", 0, "amount", "0.0000"))
                .containsEntry("excluded", null)
                .containsEntry("coverage", Map.of("requestedDays", 10, "coveredDays", 10, "missingDays", List.of()));
        assertThat(new BigDecimal((String) map(report, "matched").get("amount")))
                .isEqualByComparingTo("1030");
        assertThat(list(report, "runsUsed"))
                .extracting(r -> r.get("runId") + " " + r.get("days"))
                .containsExactly(
                        run1 + " [2026-10-01, 2026-10-02, 2026-10-03, 2026-10-04]",
                        run2 + " [2026-10-05, 2026-10-06, 2026-10-07, 2026-10-08, 2026-10-09, 2026-10-10]");
        // Run 1's MISSING_IN_GATEWAY case for Q-6 is still open, but day 6 belongs to run 2, which
        // matched Q-6: the range breakdown agrees with the range totals, not with stale cases.
        assertThat(list(report, "exceptionsByReason"))
                .extracting(r -> r.get("type") + " " + r.get("count"))
                .containsExactly("AMOUNT_MISMATCH 1");

        // A range reaching past the runs reports its gaps instead of zero activity.
        Map<String, Object> wider = report(f, "?from=2026-09-30&to=2026-10-12&currency=aed");
        assertThat(wider)
                .containsEntry("currency", "AED")
                .containsEntry("matched", Map.of("count", 11, "amount", "1030.0000"))
                .containsEntry(
                        "coverage",
                        Map.of(
                                "requestedDays",
                                13,
                                "coveredDays",
                                10,
                                "missingDays",
                                List.of("2026-09-30", "2026-10-11", "2026-10-12")));
    }

    @Test
    void aRangeWithNoRunsIsAllZeroAndAllMissing() throws Exception {
        var f = new ReportFixture(jdbc);

        Map<String, Object> report = report(f, "?from=2026-10-01&to=2026-10-02&currency=USD");

        assertThat(report)
                .containsEntry("matched", Map.of("count", 0, "amount", "0.0000"))
                .containsEntry("runsUsed", List.of())
                .containsEntry("exceptionsByReason", List.of())
                .containsEntry(
                        "coverage",
                        Map.of(
                                "requestedDays",
                                2,
                                "coveredDays",
                                0,
                                "missingDays",
                                List.of("2026-10-01", "2026-10-02")));
    }

    private UUID requestRun(ReportFixture f, String from, String to) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(f.admin.id()))
                        .with(org(f.org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"periodStart\":\"" + from + "\",\"periodEnd\":\"" + to + "\",\"currency\":\"AED\"}"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        jobRunner.runAllQueued(List.of(f.org));
        return UUID.fromString(jsonMapper
                .readValue(res.getContentAsString(), Map.class)
                .get("id")
                .toString());
    }

    private void importSample(ReportFixture f, String file, String sourceType) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file",
                                file,
                                "text/csv",
                                Files.readAllBytes(Path.of("docs/samples").resolve(file))))
                        .param("sourceType", sourceType)
                        .with(as(f.admin.id()))
                        .with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        jobRunner.runAllQueued(List.of(f.org));
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

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> json, String key) {
        return (List<Map<String, Object>>) json.get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> json, String key) {
        return (Map<String, Object>) json.get(key);
    }

    /** Map.of doesn't take null values; the API includes nulls (api-design.md §1). */
    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
