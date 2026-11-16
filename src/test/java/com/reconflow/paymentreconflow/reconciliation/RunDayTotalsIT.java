package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.1: the engine writes one row per run and anchor day, and the day rows of a completed run add
 * up to its summary columns. The date-range report sums day rows while run mode reads the summary,
 * so if the two drifted apart the same data would report two different totals.
 */
class RunDayTotalsIT extends AbstractIntegrationTest {

    static final LocalDate START = CheckpointResumeIT.START;
    static final LocalDate END = CheckpointResumeIT.END;

    @Autowired
    RunDriver driver;

    @Test
    void theDayRowsAddUpToTheRunSummary() {
        var f = new ReconFixture(jdbc);
        CheckpointResumeIT.tenDaysOfEverything(f); // every outcome, groups crossing days, a USD side
        UUID run = driver.run(f, START, END, "AED");

        assertThat(driver.status(run)).isEqualTo("COMPLETED");
        assertThat(driver.dayRows(run)).hasSize(10); // every day of the period, empty ones too
        // Sums in SQL (NUMERIC), compared as text: no double ever touches the money.
        Map<String, Object> days = jdbc.queryForMap("""
                SELECT sum(matched_count) AS matched_count, sum(matched_amount)::text AS matched_amount,
                       sum(mismatch_count) AS mismatch_count,
                       sum(mismatch_net_difference)::text AS mismatch_net_difference,
                       sum(unmatched_left_count) AS unmatched_left_count,
                       sum(unmatched_left_amount)::text AS unmatched_left_amount,
                       sum(unmatched_right_count) AS unmatched_right_count,
                       sum(unmatched_right_amount)::text AS unmatched_right_amount,
                       sum(duplicate_count) AS duplicate_count,
                       sum(currency_mismatch_count) AS currency_mismatch_count
                  FROM reconciliation_run_day WHERE run_id = ?""", run);
        Map<String, Object> summary = jdbc.queryForMap("""
                SELECT matched_count::bigint AS matched_count, matched_amount::text AS matched_amount,
                       mismatch_count::bigint AS mismatch_count,
                       mismatch_net_difference::text AS mismatch_net_difference,
                       unmatched_left_count::bigint AS unmatched_left_count,
                       unmatched_left_amount::text AS unmatched_left_amount,
                       unmatched_right_count::bigint AS unmatched_right_count,
                       unmatched_right_amount::text AS unmatched_right_amount,
                       duplicate_count::bigint AS duplicate_count,
                       currency_mismatch_count::bigint AS currency_mismatch_count
                  FROM reconciliation_run WHERE id = ?""", run);
        assertThat(days).isEqualTo(summary);
        assertThat(summary.get("matched_count")).isEqualTo(19L); // M-0..M-9, and X-0..X-8 across days
        assertThat(summary.get("mismatch_count")).isEqualTo(10L);
    }

    @Test
    void everyCaseLinkCarriesTheDayItWasObserved() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", START);
        f.gateway("PAY-1", "10.00", "AED", START.plusDays(1)); // anchored on START: a cross-day match
        f.ledger("PAY-2", "20.00", "AED", START.plusDays(1));
        UUID run = driver.run(f, START, START.plusDays(2), "AED");

        assertThat(jdbc.queryForObject("SELECT day FROM exception_case_run WHERE run_id = ?", LocalDate.class, run))
                .isEqualTo(START.plusDays(1));
        assertThat(driver.dayRows(run))
                .extracting(r -> r.get("matched_count") + "/" + r.get("unmatched_left_count"))
                .containsExactly("1/0", "0/1", "0/0");
    }
}
