package com.reconflow.paymentreconflow.reconciliation;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drives runs without the HTTP API: a PENDING run plus its RECONCILE job (what the API would
 * write), then the real queue, synchronously, for that organization only. Also reads a run's
 * outcome back as plain values, so two runs can be compared.
 */
@Component
class RunDriver {

    private final JdbcTemplate jdbc;
    private final JobRunner jobs;

    RunDriver(JdbcTemplate jdbc, JobRunner jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    UUID queueRun(ReconFixture f, LocalDate from, LocalDate to, String currency) {
        UUID run = f.pendingRun(from, to, currency);
        jdbc.update(
                "INSERT INTO job (id, organization_id, type, payload) VALUES (?, ?, 'RECONCILE', ?::jsonb)",
                Ids.newId(),
                f.org,
                "{\"runId\":\"" + run + "\"}");
        return run;
    }

    int runQueue(UUID org) {
        return jobs.runAllQueued(List.of(org));
    }

    /** Queue a run and run the queue: the run is COMPLETED (or FAILED) when this returns. */
    UUID run(ReconFixture f, LocalDate from, LocalDate to, String currency) {
        UUID run = queueRun(f, from, to, currency);
        runQueue(f.org);
        return run;
    }

    Map<String, Object> run(UUID run) {
        return jdbc.queryForMap("SELECT * FROM reconciliation_run WHERE id = ?", run);
    }

    String status(UUID run) {
        return jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE id = ?", String.class, run);
    }

    /** "ledgerId/gatewayId" per match, sorted. */
    Set<String> matchedPairs(UUID run) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT ledger_record_id || '/' || gateway_record_id FROM reconciliation_match WHERE run_id = ?",
                String.class,
                run));
    }

    /** "key -> sorted record ids" per case the run observed. */
    Set<String> observedCases(UUID run) {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT c.discrepancy_key || ' -> ' || string_agg(r.transaction_record_id::text, ',' ORDER BY r.transaction_record_id)
                  FROM exception_case_run cr
                  JOIN exception_case c ON c.id = cr.exception_id
                  JOIN exception_case_record r ON r.exception_id = c.id
                 WHERE cr.run_id = ?
                 GROUP BY c.discrepancy_key""", String.class, run));
    }

    /** The summary columns, as strings so 1.0000 and 1.0000 compare equal whatever the scale. */
    Map<String, Object> summary(UUID run) {
        return jdbc.queryForMap("""
                SELECT status, matched_count, matched_amount::text AS matched_amount, mismatch_count,
                       mismatch_net_difference::text AS mismatch_net_difference,
                       unmatched_left_count, unmatched_left_amount::text AS unmatched_left_amount,
                       unmatched_right_count, unmatched_right_amount::text AS unmatched_right_amount,
                       duplicate_count, currency_mismatch_count, excluded_count, last_completed_date
                  FROM reconciliation_run WHERE id = ?""", run);
    }

    void expireLeases(UUID org) {
        jdbc.update("UPDATE job SET lease_until = now() - interval '1 second' WHERE organization_id = ?", org);
    }

    void complete(UUID run) {
        jdbc.update("UPDATE reconciliation_run SET status = 'COMPLETED' WHERE id = ?", run);
    }
}
