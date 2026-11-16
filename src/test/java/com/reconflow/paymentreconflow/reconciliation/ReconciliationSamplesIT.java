package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Every run outcome in docs/samples/README.md, reproduced end to end: upload the sample files
 * through the real endpoint, run the import jobs, request the documented run through the API, run
 * it, and compare every record's outcome, the case keys, the summary and the invariant (NFR-01)
 * with the README. If they disagree, one of them breaks a rule in reconciliation-rules.md.
 */
class ReconciliationSamplesIT extends AbstractRunApiIT {

    static final Path SAMPLES = Path.of("docs/samples");

    @Autowired
    RunOutcomes outcomes;

    @Autowired
    RunDriver driver;

    /** §1: ledger-basic + gateway-basic, AED, 2026-10-01 to 2026-10-02. */
    @Test
    void basic() throws Exception {
        var f = new ReconFixture(jdbc);
        importSample(f, "ledger-basic.csv", "INTERNAL_LEDGER");
        importSample(f, "gateway-basic.csv", "GATEWAY");

        MockHttpServletResponse requested = requestRun(f, body("2026-10-01", "2026-10-02", "AED"), null);
        assertThat(requested.getStatus()).isEqualTo(202);
        UUID run = UUID.fromString(json(requested).get("id").toString());
        jobRunner.runAllQueued(List.of(f.org));

        assertBasicOutcome(f, run);

        // Re-running the same request returns the same run (ADR 0006), now COMPLETED.
        MockHttpServletResponse again = requestRun(f, body("2026-10-01", "2026-10-02", "AED"), null);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again)).containsEntry("id", run.toString()).containsEntry("status", "COMPLETED");
        // A fresh key makes new matches but no new cases: the four active cases are re-observed (K3).
        long cases = count("SELECT count(*) FROM exception_case WHERE organization_id = ?", f.org);
        UUID rerun = runToCompletion(f, "2026-10-01", "2026-10-02", "AED");
        assertThat(rerun).isNotEqualTo(run);
        assertThat(driver.matchedPairs(rerun)).isEqualTo(driver.matchedPairs(run));
        assertThat(count("SELECT count(*) FROM exception_case WHERE organization_id = ?", f.org))
                .isEqualTo(cases)
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM exception_case_run WHERE run_id = ? AND NOT created", rerun))
                .isEqualTo(4);
        assertThat(driver.summary(rerun)).isEqualTo(driver.summary(run));
    }

    /** §2: ledger-bom-crlf instead of ledger-basic gives exactly the §1 outcome. */
    @Test
    void encoding() throws Exception {
        var f = new ReconFixture(jdbc);
        importSample(f, "ledger-bom-crlf.csv", "INTERNAL_LEDGER");
        importSample(f, "gateway-basic.csv", "GATEWAY");

        UUID run = runToCompletion(f, "2026-10-01", "2026-10-02", "AED");

        assertBasicOutcome(f, run);
    }

    /** §0 warning: both basic ledgers in one organization are cross-file duplicates (D2). */
    @Test
    void bothBasicLedgersInOneOrganizationAreDuplicates() throws Exception {
        var f = new ReconFixture(jdbc);
        importSample(f, "ledger-basic.csv", "INTERNAL_LEDGER");
        importSample(f, "ledger-bom-crlf.csv", "INTERNAL_LEDGER");
        importSample(f, "gateway-basic.csv", "GATEWAY");

        UUID run = runToCompletion(f, "2026-10-01", "2026-10-02", "AED");

        Map<String, String> got = outcomes.byLabel(run);
        for (String ref : List.of("PAY-101", "PAY-102", "PAY-103", "PAY-104", "PAY-105", "PAY-106")) {
            assertThat(got).containsEntry(ref + "/INTERNAL_LEDGER/SUCCESS", "DUPLICATE_IN_SOURCE");
        }
        assertThat(got)
                .containsEntry("PAY-107/INTERNAL_LEDGER/FAILED", "EXCLUDED")
                .containsEntry("PAY-108/GATEWAY/SUCCESS", "MISSING_IN_LEDGER");
        assertThat(driver.summary(run)).containsEntry("matched_count", 0).containsEntry("duplicate_count", 6);
        outcomes.assertInvariant(run);
    }

    /** §3: run A (AED) then run B (USD) on 2026-10-03; one currency-mismatch case for both. */
    @Test
    void currencyMismatch() throws Exception {
        var f = new ReconFixture(jdbc);
        importSample(f, "ledger-currency-mismatch.csv", "INTERNAL_LEDGER");
        importSample(f, "gateway-currency-mismatch.csv", "GATEWAY");

        UUID runA = runToCompletion(f, "2026-10-03", "2026-10-03", "AED");

        assertThat(outcomes.byLabel(runA))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "PAY-201/INTERNAL_LEDGER/SUCCESS", "CURRENCY_MISMATCH",
                        "PAY-201/GATEWAY/SUCCESS", "CURRENCY_MISMATCH",
                        "PAY-202/INTERNAL_LEDGER/SUCCESS", "MATCHED",
                        "PAY-202/GATEWAY/SUCCESS", "MATCHED",
                        "PAY-203/GATEWAY/SUCCESS", "EXCLUDED"));
        assertThat(keys(f.org)).containsExactly("CURRENCY_MISMATCH:PAY-201:PAYMENT:AED/USD");
        Map<String, Object> caseRow = jdbc.queryForMap(
                "SELECT amount::text AS amount, amount_difference FROM exception_case WHERE organization_id = ?",
                f.org);
        assertThat(caseRow).containsEntry("amount", "400.0000").containsEntry("amount_difference", null);
        assertThat(driver.summary(runA))
                .containsEntry("matched_count", 1)
                .containsEntry("matched_amount", "150.0000")
                .containsEntry("currency_mismatch_count", 1)
                .containsEntry("mismatch_count", 0)
                .containsEntry("unmatched_left_count", 0)
                .containsEntry("unmatched_right_count", 0)
                .containsEntry("duplicate_count", 0)
                .containsEntry("excluded_count", 1);
        outcomes.assertInvariant(runA);

        UUID runB = runToCompletion(f, "2026-10-03", "2026-10-03", "USD");

        assertThat(outcomes.byLabel(runB))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "PAY-201/INTERNAL_LEDGER/SUCCESS", "CURRENCY_MISMATCH",
                        "PAY-201/GATEWAY/SUCCESS", "CURRENCY_MISMATCH",
                        "PAY-202/INTERNAL_LEDGER/SUCCESS", "EXCLUDED",
                        "PAY-202/GATEWAY/SUCCESS", "EXCLUDED",
                        "PAY-203/GATEWAY/SUCCESS", "MISSING_IN_LEDGER"));
        // The same case, re-observed by run B (K2, K3); plus run B's own missing case.
        assertThat(keys(f.org))
                .containsExactly("CURRENCY_MISMATCH:PAY-201:PAYMENT:AED/USD", "MISSING_IN_LEDGER:PAY-203:PAYMENT:USD");
        assertThat(count(
                        "SELECT count(*) FROM exception_case_run cr JOIN exception_case c ON c.id = cr.exception_id"
                                + " WHERE cr.run_id = ? AND c.type = 'CURRENCY_MISMATCH' AND NOT cr.created",
                        runB))
                .isEqualTo(1);
        assertThat(driver.summary(runB))
                .containsEntry("matched_count", 0)
                .containsEntry("currency_mismatch_count", 1)
                .containsEntry("unmatched_right_count", 1)
                .containsEntry("unmatched_right_amount", "75.0000")
                .containsEntry("excluded_count", 2);
        outcomes.assertInvariant(runB);
    }

    /** §4: gateway-duplicates, AED, 2026-10-04 (no ledger records that day). */
    @Test
    void duplicates() throws Exception {
        var f = new ReconFixture(jdbc);
        importSample(f, "gateway-duplicates.csv", "GATEWAY");

        UUID run = runToCompletion(f, "2026-10-04", "2026-10-04", "AED");

        assertThat(outcomes.byLabel(run))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "PAY-301/GATEWAY/SUCCESS", "DUPLICATE_IN_SOURCE",
                        "PAY-302/GATEWAY/SUCCESS", "DUPLICATE_IN_SOURCE",
                        "PAY-303/GATEWAY/SUCCESS", "MISSING_IN_LEDGER"));
        assertThat(cases(f.org))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "DUPLICATE_IN_SOURCE:PAY-301:PAYMENT:AED", "SAME_AMOUNT 90.0000 x3",
                        "DUPLICATE_IN_SOURCE:PAY-302:PAYMENT:AED", "DIFFERENT_AMOUNTS 60.0000 x2",
                        "MISSING_IN_LEDGER:PAY-303:PAYMENT:AED", "- 45.0000 x1"));
        assertThat(driver.summary(run))
                .containsEntry("matched_count", 0)
                .containsEntry("duplicate_count", 2)
                .containsEntry("unmatched_right_count", 1)
                .containsEntry("unmatched_right_amount", "45.0000")
                .containsEntry("unmatched_left_count", 0)
                .containsEntry("mismatch_count", 0);
        outcomes.assertInvariant(run);
    }

    /** §5: ledger-malformed, AED, 2026-10-05; only the one valid row takes part. */
    @Test
    void rowErrors() throws Exception {
        var f = new ReconFixture(jdbc);
        importSample(f, "ledger-malformed.csv", "INTERNAL_LEDGER");

        UUID run = runToCompletion(f, "2026-10-05", "2026-10-05", "AED");

        assertThat(outcomes.byLabel(run))
                .containsExactlyInAnyOrderEntriesOf(Map.of("PAY-401/INTERNAL_LEDGER/SUCCESS", "MISSING_IN_GATEWAY"));
        assertThat(cases(f.org)).containsExactly(Map.entry("MISSING_IN_GATEWAY:PAY-401:PAYMENT:AED", "- 100.0000 x1"));
        outcomes.assertInvariant(run);
    }

    private void assertBasicOutcome(ReconFixture f, UUID run) {
        assertThat(outcomes.byLabel(run))
                .containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("PAY-101/INTERNAL_LEDGER/SUCCESS", "MATCHED"),
                        Map.entry("PAY-101/GATEWAY/SUCCESS", "MATCHED"),
                        Map.entry("PAY-102/INTERNAL_LEDGER/SUCCESS", "MISSING_IN_GATEWAY"),
                        Map.entry("PAY-103/INTERNAL_LEDGER/SUCCESS", "AMOUNT_MISMATCH"),
                        Map.entry("PAY-103/GATEWAY/SUCCESS", "AMOUNT_MISMATCH"),
                        Map.entry("PAY-104/INTERNAL_LEDGER/SUCCESS", "DUPLICATE_IN_SOURCE"),
                        Map.entry("PAY-104/GATEWAY/SUCCESS", "DUPLICATE_IN_SOURCE"),
                        Map.entry("PAY-105/INTERNAL_LEDGER/SUCCESS", "MATCHED"),
                        Map.entry("PAY-105/GATEWAY/SUCCESS", "MATCHED"),
                        Map.entry("PAY-106/INTERNAL_LEDGER/SUCCESS", "MATCHED"),
                        Map.entry("PAY-106/GATEWAY/SUCCESS", "MATCHED"),
                        Map.entry("PAY-107/INTERNAL_LEDGER/FAILED", "EXCLUDED"),
                        Map.entry("PAY-108/GATEWAY/SUCCESS", "MISSING_IN_LEDGER")));
        assertThat(cases(f.org))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "MISSING_IN_GATEWAY:PAY-102:PAYMENT:AED", "- 500.0000 x1",
                        "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED", "- 750.0000 x2 diff -50.0000",
                        "DUPLICATE_IN_SOURCE:PAY-104:PAYMENT:AED", "SAME_AMOUNT 250.0000 x3",
                        "MISSING_IN_LEDGER:PAY-108:PAYMENT:AED", "- 200.0000 x1"));
        assertThat(driver.summary(run))
                .containsEntry("matched_count", 3)
                .containsEntry("matched_amount", "1420.5000")
                .containsEntry("mismatch_count", 1)
                .containsEntry("mismatch_net_difference", "-50.0000")
                .containsEntry("unmatched_left_count", 1)
                .containsEntry("unmatched_left_amount", "500.0000")
                .containsEntry("unmatched_right_count", 1)
                .containsEntry("unmatched_right_amount", "200.0000")
                .containsEntry("duplicate_count", 1)
                .containsEntry("currency_mismatch_count", 0)
                .containsEntry("excluded_count", 1);
        // 14 records in = 3×2 + 1×2 + 2 + 3 + 1 (README §1).
        assertThat(outcomes.byRecord(run)).hasSize(14);
        outcomes.assertInvariant(run);
    }

    /** Discrepancy key → "reason amount xRecords [diff d]", for the organization's cases. */
    private Map<String, String> cases(UUID org) {
        Map<String, String> cases = new TreeMap<>();
        jdbc.query(
                """
                SELECT c.discrepancy_key, coalesce(c.reason_code, '-') AS reason, c.amount::text AS amount,
                       c.amount_difference::text AS diff,
                       (SELECT count(*) FROM exception_case_record r WHERE r.exception_id = c.id) AS records
                  FROM exception_case c WHERE c.organization_id = ?""",
                rs -> {
                    String diff = rs.getString("diff");
                    cases.put(
                            rs.getString("discrepancy_key"),
                            rs.getString("reason") + " " + rs.getString("amount") + " x" + rs.getLong("records")
                                    + (diff == null ? "" : " diff " + diff));
                },
                org);
        return cases;
    }

    private List<String> keys(UUID org) {
        return jdbc.queryForList(
                "SELECT discrepancy_key FROM exception_case WHERE organization_id = ? ORDER BY discrepancy_key",
                String.class,
                org);
    }

    private void importSample(ReconFixture f, String file, String sourceType) throws Exception {
        UUID uploader = data().newMember(f.org, Role.UPLOADER).id();
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file", file, "text/csv", Files.readAllBytes(SAMPLES.resolve(file))))
                        .param("sourceType", sourceType)
                        .with(as(uploader))
                        .with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        jobRunner.runAllQueued(List.of(f.org));
    }
}
