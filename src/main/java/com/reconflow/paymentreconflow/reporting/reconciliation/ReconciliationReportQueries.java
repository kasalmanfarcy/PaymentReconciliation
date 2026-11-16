package com.reconflow.paymentreconflow.reporting.reconciliation;

import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.ReasonCount;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.ResolverCount;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every SQL statement of the reconciliation report (M8.1), and nothing else. Read-model SQL over
 * other modules' tables (phase-M8 §2.4, architecture.md §4): {@code reporting} never writes them
 * and never imports their Java types, and all of this SQL lives here, so a renamed column has one
 * place to be fixed, and the report ITs run it against the migrated schema in CI.
 *
 * <p>Every statement returns final aggregates. Money is summed by PostgreSQL in {@code NUMERIC}
 * and read back as {@link BigDecimal}; Java never adds amounts up. {@code coalesce(sum(...), 0)}
 * everywhere, because {@code sum()} over no rows is NULL, not 0; the literal {@code 0.0000} keeps
 * the stored scale, so an empty sum reads {@code "0.0000"} like any other amount.
 *
 * <p>Left/right become ledger/gateway here: a run's summary columns are per <em>side</em>
 * (left = the run's left source), the report's per <em>source</em>, so a run with the sources
 * swapped still reports its ledger records as the ledger's.
 */
@Component
@Transactional(readOnly = true)
public class ReconciliationReportQueries {

    /** A run row: a primary-key lookup, scoped by organization (another tenant's run: no row). */
    private static final String RUN = """
            SELECT r.id, r.status, r.currency::text AS currency, r.period_start, r.period_end, r.completed_at,
                   r.matched_count, r.matched_amount,
                   CASE WHEN r.left_source = 'INTERNAL_LEDGER' THEN r.unmatched_left_count
                        ELSE r.unmatched_right_count END                                  AS unmatched_ledger_count,
                   CASE WHEN r.left_source = 'INTERNAL_LEDGER' THEN r.unmatched_left_amount
                        ELSE r.unmatched_right_amount END                                 AS unmatched_ledger_amount,
                   CASE WHEN r.left_source = 'INTERNAL_LEDGER' THEN r.unmatched_right_count
                        ELSE r.unmatched_left_count END                                   AS unmatched_gateway_count,
                   CASE WHEN r.left_source = 'INTERNAL_LEDGER' THEN r.unmatched_right_amount
                        ELSE r.unmatched_left_amount END                                  AS unmatched_gateway_amount,
                   r.mismatch_count, r.mismatch_net_difference, r.duplicate_count, r.currency_mismatch_count,
                   r.excluded_count
              FROM reconciliation_run r
             WHERE r.organization_id = :orgId AND r.id = :runId
            """;

    /**
     * For each day in range, the authoritative run: the most recently completed run of this
     * organization and currency whose day rows include that day (phase-M8 §2.3). {@code DISTINCT ON
     * (day)} keeps the first row per day in {@code ORDER BY} order, i.e. the latest completion; the
     * run id breaks ties so the choice is deterministic. Served by
     * {@code reconciliation_run_day_lookup_idx (organization_id, currency, day)}.
     */
    private static final String AUTHORITATIVE = """
            WITH authoritative AS (
                SELECT DISTINCT ON (d.day)
                       d.day, d.run_id, r.completed_at, r.left_source,
                       d.matched_count, d.matched_amount, d.mismatch_count, d.mismatch_net_difference,
                       d.unmatched_left_count, d.unmatched_left_amount,
                       d.unmatched_right_count, d.unmatched_right_amount,
                       d.duplicate_count, d.currency_mismatch_count
                  FROM reconciliation_run_day d
                  JOIN reconciliation_run r ON r.id = d.run_id AND r.organization_id = d.organization_id
                 WHERE d.organization_id = :orgId
                   AND d.currency        = :currency
                   AND d.day BETWEEN :from AND :to
                   AND r.status = 'COMPLETED'
                 ORDER BY d.day, r.completed_at DESC NULLS LAST, r.id DESC
            )
            """;

    private static final String RANGE_TOTALS = AUTHORITATIVE + """
            SELECT coalesce(sum(matched_count), 0)            AS matched_count,
                   coalesce(sum(matched_amount), 0.0000)           AS matched_amount,
                   coalesce(sum(CASE WHEN left_source = 'INTERNAL_LEDGER' THEN unmatched_left_count
                                     ELSE unmatched_right_count END), 0)  AS unmatched_ledger_count,
                   coalesce(sum(CASE WHEN left_source = 'INTERNAL_LEDGER' THEN unmatched_left_amount
                                     ELSE unmatched_right_amount END), 0.0000) AS unmatched_ledger_amount,
                   coalesce(sum(CASE WHEN left_source = 'INTERNAL_LEDGER' THEN unmatched_right_count
                                     ELSE unmatched_left_count END), 0)   AS unmatched_gateway_count,
                   coalesce(sum(CASE WHEN left_source = 'INTERNAL_LEDGER' THEN unmatched_right_amount
                                     ELSE unmatched_left_amount END), 0.0000)  AS unmatched_gateway_amount,
                   coalesce(sum(mismatch_count), 0)           AS mismatch_count,
                   coalesce(sum(mismatch_net_difference), 0.0000)  AS mismatch_net_difference,
                   coalesce(sum(duplicate_count), 0)          AS duplicate_count,
                   coalesce(sum(currency_mismatch_count), 0)  AS currency_mismatch_count
              FROM authoritative
            """;

    private static final String RANGE_DAYS = AUTHORITATIVE + """
            SELECT day, run_id, completed_at FROM authoritative ORDER BY day
            """;

    /** The cases one run observed (exception_case_run's key leads with run_id). */
    private static final String RUN_CASES = """
            WITH cases AS (
                SELECT cr.exception_id
                  FROM exception_case_run cr
                 WHERE cr.organization_id = :orgId AND cr.run_id = :runId
            )
            """;

    /**
     * The cases the authoritative runs observed <em>on their authoritative days</em>: the same
     * (run, day) pairs whose day rows the totals add up, so the breakdown agrees with the totals.
     * DISTINCT because a case spanning days can be observed by two authoritative runs.
     */
    private static final String RANGE_CASES = AUTHORITATIVE + """
            , cases AS (
                SELECT DISTINCT cr.exception_id
                  FROM authoritative a
                  JOIN exception_case_run cr
                    ON cr.run_id = a.run_id AND cr.day = a.day AND cr.organization_id = :orgId
            )
            """;

    /** One pass over the cases: FILTER computes every status count in the same scan. */
    private static final String BY_REASON = """
            SELECT e.type,
                   e.reason_code,
                   count(*)                                       AS total,
                   count(*) FILTER (WHERE e.status = 'OPEN')      AS open,
                   count(*) FILTER (WHERE e.status = 'IN_REVIEW') AS in_review,
                   count(*) FILTER (WHERE e.status = 'RESOLVED')  AS resolved,
                   coalesce(sum(e.amount), 0.0000)                     AS amount,
                   coalesce(sum(e.amount_difference), 0.0000)          AS amount_difference
              FROM cases c
              JOIN exception_case e ON e.id = c.exception_id AND e.organization_id = :orgId
             GROUP BY e.type, e.reason_code
             ORDER BY total DESC, e.type, e.reason_code NULLS FIRST
            """;

    private static final String RESOLVERS = """
            SELECT e.resolved_by AS user_id, u.display_name, count(*) AS resolved
              FROM cases c
              JOIN exception_case e ON e.id = c.exception_id AND e.organization_id = :orgId
              JOIN app_user u ON u.id = e.resolved_by
             WHERE e.status = 'RESOLVED'
             GROUP BY e.resolved_by, u.display_name
             ORDER BY resolved DESC, u.display_name, e.resolved_by
            """;

    private final JdbcClient jdbc;

    ReconciliationReportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The precomputed totals, mapped from side to source. */
    public record Totals(
            long matchedCount,
            BigDecimal matchedAmount,
            long unmatchedLedgerCount,
            BigDecimal unmatchedLedgerAmount,
            long unmatchedGatewayCount,
            BigDecimal unmatchedGatewayAmount,
            long mismatchCount,
            BigDecimal mismatchNetDifference,
            long duplicateCount,
            long currencyMismatchCount) {}

    public record RunRow(
            UUID id,
            String status,
            String currency,
            LocalDate periodStart,
            LocalDate periodEnd,
            @Nullable Instant completedAt,
            Totals totals,
            long excludedCount) {}

    /** The run that was authoritative for one day. */
    public record DayRun(
            LocalDate day, UUID runId, @Nullable Instant completedAt) {}

    public Optional<RunRow> run(UUID orgId, UUID runId) {
        return jdbc.sql(RUN)
                .param("orgId", orgId)
                .param("runId", runId)
                .query((rs, n) -> new RunRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("currency"),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        instant(rs, "completed_at"),
                        totals(rs),
                        rs.getLong("excluded_count")))
                .optional();
    }

    public List<ReasonCount> reasonsForRun(UUID orgId, UUID runId) {
        return jdbc.sql(RUN_CASES + BY_REASON)
                .param("orgId", orgId)
                .param("runId", runId)
                .query(ReconciliationReportQueries::reason)
                .list();
    }

    public List<ResolverCount> resolversForRun(UUID orgId, UUID runId) {
        return jdbc.sql(RUN_CASES + RESOLVERS)
                .param("orgId", orgId)
                .param("runId", runId)
                .query(ReconciliationReportQueries::resolver)
                .list();
    }

    public Totals rangeTotals(UUID orgId, LocalDate from, LocalDate to, String currency) {
        return range(RANGE_TOTALS, orgId, from, to, currency)
                .query((rs, n) -> totals(rs))
                .single();
    }

    public List<DayRun> rangeDays(UUID orgId, LocalDate from, LocalDate to, String currency) {
        return range(RANGE_DAYS, orgId, from, to, currency)
                .query((rs, n) -> new DayRun(
                        rs.getObject("day", LocalDate.class),
                        rs.getObject("run_id", UUID.class),
                        instant(rs, "completed_at")))
                .list();
    }

    public List<ReasonCount> rangeReasons(UUID orgId, LocalDate from, LocalDate to, String currency) {
        return range(RANGE_CASES + BY_REASON, orgId, from, to, currency)
                .query(ReconciliationReportQueries::reason)
                .list();
    }

    public List<ResolverCount> rangeResolvers(UUID orgId, LocalDate from, LocalDate to, String currency) {
        return range(RANGE_CASES + RESOLVERS, orgId, from, to, currency)
                .query(ReconciliationReportQueries::resolver)
                .list();
    }

    private JdbcClient.StatementSpec range(String sql, UUID orgId, LocalDate from, LocalDate to, String currency) {
        return jdbc.sql(sql)
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("currency", currency);
    }

    private static Totals totals(ResultSet rs) throws SQLException {
        return new Totals(
                rs.getLong("matched_count"),
                rs.getBigDecimal("matched_amount"),
                rs.getLong("unmatched_ledger_count"),
                rs.getBigDecimal("unmatched_ledger_amount"),
                rs.getLong("unmatched_gateway_count"),
                rs.getBigDecimal("unmatched_gateway_amount"),
                rs.getLong("mismatch_count"),
                rs.getBigDecimal("mismatch_net_difference"),
                rs.getLong("duplicate_count"),
                rs.getLong("currency_mismatch_count"));
    }

    private static ReasonCount reason(ResultSet rs, int n) throws SQLException {
        return new ReasonCount(
                rs.getString("type"),
                rs.getString("reason_code"),
                rs.getLong("total"),
                rs.getLong("open"),
                rs.getLong("in_review"),
                rs.getLong("resolved"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("amount_difference"));
    }

    private static ResolverCount resolver(ResultSet rs, int n) throws SQLException {
        return new ResolverCount(
                rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getLong("resolved"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
