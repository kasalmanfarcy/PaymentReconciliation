package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M6.20: running the same reconciliation twice produces no duplicate matches and no duplicate
 * active exceptions. With the same key it is the same run; with a fresh key it is an independent
 * run with its own matches that re-observes, rather than re-creates, the active cases (K3).
 */
class RerunIT extends AbstractRunApiIT {

    static final LocalDate D1 = LocalDate.of(2026, 10, 1);

    @Autowired
    RunDriver driver;

    static void data(ReconFixture f) {
        f.ledger("PAY-101", "1000.00", "AED", D1);
        f.gateway("PAY-101", "1000.00", "AED", D1);
        f.ledger("PAY-102", "500.00", "AED", D1);
        f.ledger("PAY-103", "750.00", "AED", D1.plusDays(1));
        f.gateway("PAY-103", "700.00", "AED", D1.plusDays(1));
        f.gateway("PAY-104", "250.00", "AED", D1);
        f.gateway("PAY-104", "250.00", "AED", D1.plusDays(1));
    }

    @Test
    void theSameKeyTwiceIsOneRunOneJobOneSetOfMatches() throws Exception {
        var f = new ReconFixture(jdbc);
        data(f);
        String body = body("2026-10-01", "2026-10-02", "AED");

        MockHttpServletResponse first = requestRun(f, body, "month-end");
        jobRunner.runAllQueued(List.of(f.org));
        MockHttpServletResponse second = requestRun(f, body, "month-end");
        jobRunner.runAllQueued(List.of(f.org));

        assertThat(first.getStatus()).isEqualTo(202);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(json(second).get("id")).isEqualTo(json(first).get("id"));
        UUID run = UUID.fromString(json(first).get("id").toString());
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", f.org))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", f.org))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation_match WHERE run_id = ?", run))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM exception_case WHERE organization_id = ?", f.org))
                .isEqualTo(3);
    }

    @Test
    void aFreshKeyIsAnIndependentRunThatCreatesNoNewCases() throws Exception {
        var f = new ReconFixture(jdbc);
        data(f);
        UUID first = runToCompletion(f, "2026-10-01", "2026-10-02", "AED");
        List<Map<String, Object>> casesAfterFirst = cases(f.org);

        UUID second = runToCompletion(f, "2026-10-01", "2026-10-02", "AED");

        assertThat(second).isNotEqualTo(first);
        assertThat(cases(f.org)).as("the same active case rows, untouched").isEqualTo(casesAfterFirst);
        assertThat(count("SELECT count(*) FROM exception_case WHERE organization_id = ?", f.org))
                .isEqualTo(3);
        // Each run holds its own matches over the same records.
        assertThat(count("SELECT count(*) FROM reconciliation_match WHERE run_id = ?", first))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation_match WHERE run_id = ?", second))
                .isEqualTo(1);
        assertThat(driver.matchedPairs(second)).isEqualTo(driver.matchedPairs(first));
        // exception_case_run links both runs to every case: created by the first, re-observed by the second.
        assertThat(jdbc.queryForList(
                        "SELECT run_id, count(*) AS n, bool_and(created) AS created FROM exception_case_run"
                                + " WHERE organization_id = ? GROUP BY run_id",
                        f.org))
                .containsExactlyInAnyOrder(
                        Map.of("run_id", first, "n", 3L, "created", true),
                        Map.of("run_id", second, "n", 3L, "created", false));
        assertThat(driver.summary(second)).isEqualTo(driver.summary(first));
        assertThat(count(
                        "SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'EXCEPTION_CREATED'",
                        f.org))
                .isEqualTo(3);
    }

    @Test
    void anOverlappingPeriodSharesTheCases() throws Exception {
        // The key has no period in it (K2): "1-2 Oct" then "2 Oct" see the same PAY-103 mismatch.
        var f = new ReconFixture(jdbc);
        data(f);
        runToCompletion(f, "2026-10-01", "2026-10-02", "AED");

        UUID narrow = runToCompletion(f, "2026-10-02", "2026-10-02", "AED");

        assertThat(count(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ? AND type = 'AMOUNT_MISMATCH'",
                        f.org))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM exception_case_run cr JOIN exception_case c ON c.id = cr.exception_id"
                                + " WHERE cr.run_id = ? AND c.type = 'AMOUNT_MISMATCH' AND NOT cr.created",
                        narrow))
                .isEqualTo(1);
    }

    private List<Map<String, Object>> cases(UUID org) {
        return jdbc.queryForList(
                "SELECT id, discrepancy_key, status, run_id, version, updated_at FROM exception_case"
                        + " WHERE organization_id = ? ORDER BY discrepancy_key",
                org);
    }
}
