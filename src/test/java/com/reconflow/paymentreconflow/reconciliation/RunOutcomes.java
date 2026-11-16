package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Where every record ended up in a run: {@code MATCHED}, the type of the one case that links it, or
 * {@code EXCLUDED}. Also checks the invariant of reconciliation-rules.md §4 (NFR-01) on the run's
 * own summary: records in = matched×2 + amount mismatches×2 + currency mismatches×2 + missing +
 * records of duplicate cases + excluded, and no record is in two places.
 */
@Component
class RunOutcomes {

    private static final String PLACEMENTS = """
            SELECT t.id,
                   t.normalized_reference || '/' || t.source_type || '/' || t.status AS label,
                   (SELECT count(*) FROM reconciliation_match m
                     WHERE m.run_id = r.id AND (m.ledger_record_id = t.id OR m.gateway_record_id = t.id)) AS matches,
                   (SELECT count(*) FROM exception_case_run cr
                      JOIN exception_case_record l ON l.exception_id = cr.exception_id
                     WHERE cr.run_id = r.id AND l.transaction_record_id = t.id) AS cases,
                   (SELECT min(c.type) FROM exception_case_run cr
                      JOIN exception_case c ON c.id = cr.exception_id
                      JOIN exception_case_record l ON l.exception_id = c.id
                     WHERE cr.run_id = r.id AND l.transaction_record_id = t.id) AS case_type
              FROM reconciliation_run r
              JOIN transaction_record t
                ON t.organization_id = r.organization_id
               AND t.transaction_date BETWEEN r.period_start AND r.period_end
               AND t.source_type IN (r.left_source, r.right_source)
              JOIN import_batch b ON b.id = t.batch_id AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
             WHERE r.id = ?
            """;

    private final JdbcTemplate jdbc;

    RunOutcomes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Record id → outcome, for every record of the run's organization, sources and period. */
    Map<UUID, String> byRecord(UUID run) {
        Map<UUID, String> outcomes = new TreeMap<>();
        jdbc.query(
                PLACEMENTS,
                rs -> {
                    long matches = rs.getLong("matches");
                    long cases = rs.getLong("cases");
                    assertThat(matches + cases)
                            .as("record %s is in at most one place", rs.getString("label"))
                            .isLessThanOrEqualTo(1);
                    outcomes.put(
                            rs.getObject("id", UUID.class),
                            matches == 1 ? "MATCHED" : cases == 1 ? rs.getString("case_type") : "EXCLUDED");
                },
                run);
        return outcomes;
    }

    /** "REF/SOURCE/STATUS" → outcome; for samples, whose references are unique per source. */
    Map<String, String> byLabel(UUID run) {
        Map<String, String> outcomes = new TreeMap<>();
        jdbc.query(
                PLACEMENTS,
                rs -> {
                    String label = rs.getString("label");
                    long matches = rs.getLong("matches");
                    long cases = rs.getLong("cases");
                    String outcome = matches == 1 ? "MATCHED" : cases == 1 ? rs.getString("case_type") : "EXCLUDED";
                    String previous = outcomes.put(label, outcome);
                    assertThat(previous == null || previous.equals(outcome))
                            .as("every copy of %s has one outcome", label)
                            .isTrue();
                },
                run);
        return outcomes;
    }

    /** NFR-01 on the run's own summary columns. */
    void assertInvariant(UUID run) {
        Map<String, Object> s = jdbc.queryForMap("SELECT * FROM reconciliation_run WHERE id = ?", run);
        assertThat(s.get("status")).isEqualTo("COMPLETED");
        Map<UUID, String> outcomes = byRecord(run);
        long recordsIn = outcomes.size();
        long duplicateRecords =
                outcomes.values().stream().filter("DUPLICATE_IN_SOURCE"::equals).count();
        long accountedFor = 2L * (int) s.get("matched_count")
                + 2L * (int) s.get("mismatch_count")
                + 2L * (int) s.get("currency_mismatch_count")
                + (int) s.get("unmatched_left_count")
                + (int) s.get("unmatched_right_count")
                + duplicateRecords
                + (int) s.get("excluded_count");
        assertThat(accountedFor)
                .as("NFR-01: every record of the run in exactly one place")
                .isEqualTo(recordsIn);
        assertThat(outcomes.values().stream().filter("EXCLUDED"::equals).count())
                .as("excluded records counted, never silently dropped")
                .isEqualTo(((Integer) s.get("excluded_count")).longValue());
    }
}
