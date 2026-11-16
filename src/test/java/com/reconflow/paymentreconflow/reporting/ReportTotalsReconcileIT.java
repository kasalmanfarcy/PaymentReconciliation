package com.reconflow.paymentreconflow.reporting;

import static com.reconflow.paymentreconflow.reporting.ReconciliationReportIT.list;
import static com.reconflow.paymentreconflow.reporting.ReconciliationReportIT.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M8.6: the report totals reconcile against raw counts. Every M1.4 sample that can share an
 * organization is uploaded through the API, one run reconciles 1–5 October, and every number of the
 * reports is checked twice:
 *
 * <ul>
 *   <li>against <b>raw counts</b>: plain SQL over matches, case links and the records themselves,
 *       written here without reusing the engine or report code (otherwise it would compare a query
 *       with itself). This catches drift between the precomputed summary and the data;</li>
 *   <li>against <b>hard-coded values</b> worked out by hand from docs/samples/README.md. This
 *       catches bugs that affect both paths equally.</li>
 * </ul>
 *
 * Amounts are compared with {@code compareTo} (AssertJ's {@code isEqualByComparingTo}), never
 * {@code equals}: 10.0000 and 10 are the same money.
 */
class ReportTotalsReconcileIT extends AbstractIntegrationTest {

    @Autowired
    JobRunner jobRunner;

    @Test
    void theReportsAgreeWithRawCountsAndTheSampleREADME() throws Exception {
        var f = new ReportFixture(jdbc);
        for (String[] sample : new String[][] {
            {"ledger-basic.csv", "INTERNAL_LEDGER"},
            {"gateway-basic.csv", "GATEWAY"},
            {"ledger-currency-mismatch.csv", "INTERNAL_LEDGER"},
            {"gateway-currency-mismatch.csv", "GATEWAY"},
            {"gateway-duplicates.csv", "GATEWAY"},
            {"ledger-malformed.csv", "INTERNAL_LEDGER"},
        }) {
            importSample(f, sample[0], sample[1]);
        }
        UUID run = requestRun(f);
        resolveOneMismatch(f);

        Map<String, Object> report = report(f, "/api/v1/reports/reconciliation?runId=" + run);

        // 1. Hard-coded: README §1 (basic), §3 (AED side of the currency mismatch), §4, §5.
        assertMoney(report, "matched", 4, "1570.50"); // 1000 + 300 + 120.5 + 150
        assertMoney(report, "unmatchedLedger", 2, "600.00"); // PAY-102 500, PAY-401 100
        assertMoney(report, "unmatchedGateway", 2, "245.00"); // PAY-108 200, PAY-303 45
        assertThat(count(map(report, "mismatches"))).isEqualTo(1);
        assertThat(money(map(report, "mismatches"), "netDifference")).isEqualByComparingTo("-50");
        assertThat(count(map(report, "duplicates"))).isEqualTo(3); // PAY-104, PAY-301, PAY-302
        assertThat(count(map(report, "currencyMismatches"))).isEqualTo(1); // PAY-201
        assertThat(count(map(report, "excluded"))).isEqualTo(2); // PAY-107 FAILED, PAY-203 USD only

        // 2. Raw counts, straight from the tables.
        assertThat(count(map(report, "matched")))
                .isEqualTo(raw("SELECT count(*) FROM reconciliation_match WHERE run_id = ?", run));
        assertThat(money(map(report, "matched"), "amount")).isEqualByComparingTo(rawMoney("""
                SELECT sum(t.amount) FROM reconciliation_match m
                  JOIN transaction_record t ON t.id = m.ledger_record_id AND t.transaction_date = m.ledger_txn_date
                 WHERE m.run_id = ?""", run));
        // The gateway side of every exact match adds up to the same figure.
        assertThat(money(map(report, "matched"), "amount")).isEqualByComparingTo(rawMoney("""
                SELECT sum(t.amount) FROM reconciliation_match m
                  JOIN transaction_record t ON t.id = m.gateway_record_id AND t.transaction_date = m.gateway_txn_date
                 WHERE m.run_id = ?""", run));
        assertThat(count(map(report, "unmatchedLedger"))).isEqualTo(casesOfType(run, "MISSING_IN_GATEWAY"));
        assertThat(count(map(report, "unmatchedGateway"))).isEqualTo(casesOfType(run, "MISSING_IN_LEDGER"));
        // Amounts from the linked records themselves, not from the cases' amount column.
        assertThat(money(map(report, "unmatchedLedger"), "amount"))
                .isEqualByComparingTo(linkedRecordAmount(run, "MISSING_IN_GATEWAY", "INTERNAL_LEDGER"));
        assertThat(money(map(report, "unmatchedGateway"), "amount"))
                .isEqualByComparingTo(linkedRecordAmount(run, "MISSING_IN_LEDGER", "GATEWAY"));
        assertThat(money(map(report, "mismatches"), "netDifference"))
                .isEqualByComparingTo(linkedRecordAmount(run, "AMOUNT_MISMATCH", "GATEWAY")
                        .subtract(linkedRecordAmount(run, "AMOUNT_MISMATCH", "INTERNAL_LEDGER")));
        assertThat(count(map(report, "duplicates"))).isEqualTo(casesOfType(run, "DUPLICATE_IN_SOURCE"));
        assertThat(count(map(report, "currencyMismatches"))).isEqualTo(casesOfType(run, "CURRENCY_MISMATCH"));

        // 3. Conservation: every record of the period (both sources, accepted batches, any status or
        //    currency) is in exactly one place: a match, a case, or excluded.
        long inPeriod = raw("""
                SELECT count(*) FROM transaction_record t JOIN import_batch b ON b.id = t.batch_id
                 WHERE t.organization_id = ? AND t.transaction_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-05'
                   AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')""", f.org);
        assertThat(2 * count(map(report, "matched"))
                        + 2 * count(map(report, "mismatches"))
                        + count(map(report, "unmatchedLedger"))
                        + count(map(report, "unmatchedGateway"))
                        + recordsInCases(run, "DUPLICATE_IN_SOURCE")
                        + recordsInCases(run, "CURRENCY_MISMATCH")
                        + count(map(report, "excluded")))
                .isEqualTo(inPeriod)
                .isEqualTo(26); // README: 14 basic + 5 currency + 6 duplicates + 1 valid malformed row
        // 4. The breakdown by reason adds up to the cases, and the status split to raw statuses.
        List<Map<String, Object>> reasons = list(report, "exceptionsByReason");
        assertThat(reasons.stream().mapToLong(ReportTotalsReconcileIT::count).sum())
                .isEqualTo(raw("SELECT count(*) FROM exception_case_run WHERE run_id = ?", run))
                .isEqualTo(9);
        Map<String, Object> resolution = map(report, "resolution");
        assertThat(((Number) resolution.get("resolved")).longValue())
                .isEqualTo(raw("""
                        SELECT count(*) FROM exception_case_run cr JOIN exception_case e ON e.id = cr.exception_id
                         WHERE cr.run_id = ? AND e.status = 'RESOLVED'""", run))
                .isEqualTo(1);
        assertThat(((Number) resolution.get("open")).longValue()).isEqualTo(8);

        // 5. Date-range mode over the run's own days reads the day rows: the same totals.
        Map<String, Object> range =
                report(f, "/api/v1/reports/reconciliation?from=2026-10-01&to=2026-10-05&currency=AED");
        for (String section : List.of(
                "matched", "unmatchedLedger", "unmatchedGateway", "mismatches", "duplicates", "currencyMismatches")) {
            assertThat(normalise(map(range, section))).as(section).isEqualTo(normalise(map(report, section)));
        }
        assertThat(list(range, "exceptionsByReason")).hasSameElementsAs(reasons);

        // 6. Ageing: the active cases and their amount at stake, against a raw count and sum.
        Map<String, Object> ageing = report(f, "/api/v1/reports/exception-ageing");
        long active = list(ageing, "buckets").stream()
                .mapToLong(ReportTotalsReconcileIT::cases)
                .sum();
        assertThat(active)
                .isEqualTo(raw(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ? AND status <> 'RESOLVED'",
                        f.org));
        BigDecimal aed = list(ageing, "buckets").stream()
                .flatMap(b -> listOf(b, "amountAtStake").stream())
                .filter(m -> m.get("currency").equals("AED"))
                .map(m -> new BigDecimal((String) m.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(aed)
                .isEqualByComparingTo(rawMoney(
                        "SELECT sum(amount) FROM exception_case WHERE organization_id = ? AND status <> 'RESOLVED'"
                                + " AND currency = 'AED'",
                        f.org));

        // 7. Import quality of the malformed file against its raw error rows.
        UUID malformed = jdbc.queryForObject(
                "SELECT id FROM import_batch WHERE organization_id = ? AND file_name = 'ledger-malformed.csv'",
                UUID.class,
                f.org);
        Map<String, Object> quality = map(report(f, "/api/v1/reports/import-quality?batchId=" + malformed), "batch");
        assertThat(map(quality, "stored"))
                .isEqualTo(Map.of(
                        "errors",
                        (int) raw("SELECT count(*) FROM import_row_error WHERE batch_id = ?", malformed),
                        "errorRows",
                        (int) raw(
                                "SELECT count(DISTINCT row_number) FROM import_row_error WHERE batch_id = ?",
                                malformed)));
    }

    private long casesOfType(UUID run, String type) {
        return raw("""
                SELECT count(*) FROM exception_case_run cr JOIN exception_case e ON e.id = cr.exception_id
                 WHERE cr.run_id = ? AND e.type = ?""", run, type);
    }

    private long recordsInCases(UUID run, String type) {
        return raw("""
                SELECT count(*) FROM exception_case_run cr
                  JOIN exception_case e ON e.id = cr.exception_id
                  JOIN exception_case_record r ON r.exception_id = e.id
                 WHERE cr.run_id = ? AND e.type = ?""", run, type);
    }

    private BigDecimal linkedRecordAmount(UUID run, String type, String source) {
        return rawMoney("""
                SELECT coalesce(sum(t.amount), 0) FROM exception_case_run cr
                  JOIN exception_case e ON e.id = cr.exception_id
                  JOIN exception_case_record r ON r.exception_id = e.id
                  JOIN transaction_record t ON t.id = r.transaction_record_id AND t.transaction_date = r.transaction_date
                 WHERE cr.run_id = ? AND e.type = ? AND t.source_type = ?""", run, type, source);
    }

    private long raw(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private BigDecimal rawMoney(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void assertMoney(Map<String, Object> report, String section, long count, String amount) {
        assertThat(count(map(report, section))).as(section).isEqualTo(count);
        assertThat(money(map(report, section), "amount")).as(section).isEqualByComparingTo(amount);
    }

    private static long count(Map<String, Object> section) {
        return ((Number) section.get("count")).longValue();
    }

    private static long cases(Map<String, Object> bucket) {
        return ((Number) bucket.get("cases")).longValue();
    }

    /** Money comes as a JSON string (api-design.md §1): parse it, never through a double. */
    private static BigDecimal money(Map<String, Object> section, String key) {
        return new BigDecimal((String) section.get(key));
    }

    /** Amount strings by value, so "1.50" and "1.5000" compare equal. */
    private static Map<String, Object> normalise(Map<String, Object> section) {
        Map<String, Object> copy = new java.util.TreeMap<>();
        section.forEach((k, v) -> copy.put(k, v instanceof String s ? new BigDecimal(s).stripTrailingZeros() : v));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> json, String key) {
        return (List<Map<String, Object>>) json.get(key);
    }

    private void resolveOneMismatch(ReportFixture f) throws Exception {
        UUID mismatch = jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND type = 'AMOUNT_MISMATCH'",
                UUID.class,
                f.org);
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/exceptions/" + mismatch + "/resolve")
                        .with(as(f.investigator.id()))
                        .with(org(f.org))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionCode\":\"GATEWAY_FEE\"}"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    }

    private UUID requestRun(ReportFixture f) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(f.admin.id()))
                        .with(org(f.org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-05\",\"currency\":\"AED\"}"))
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
                        .file(new org.springframework.mock.web.MockMultipartFile(
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> report(ReportFixture f, String path) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(
                        get(path).with(as(f.viewer.id())).with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }
}
