package com.reconflow.paymentreconflow.reporting.ageing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ageing report's SQL (M8.3), read-model SQL over {@code exceptions}' table (phase-M8 §2.4).
 *
 * <p>{@code width_bucket(age, ARRAY[1, 3, 7, 30])} returns 0 below 1 day, 1 for [1, 3), 2 for
 * [3, 7), 3 for [7, 30) and 4 from 30 days: the five buckets, each lower bound inclusive. Age is
 * measured against {@code now()}, the <em>transaction's</em> start, so every case in one report
 * is aged against the same instant ({@code clock_timestamp()} would drift while the query runs).
 *
 * <p>{@code GROUPING SETS} returns the per-assignee rows and the per-bucket totals in one pass; the
 * amounts are summed by PostgreSQL, per currency.
 */
@Component
@Transactional(readOnly = true)
public class ExceptionAgeingQueries {

    private static final String SELECT_ACTIVE = """
            WITH active AS (
                SELECT width_bucket(extract(epoch FROM (now() - e.created_at)) / 86400.0,
                                    ARRAY[1, 3, 7, 30]::numeric[]) AS bucket,
                       e.assigned_to, e.currency::text AS currency, e.amount
                  FROM exception_case e
                 WHERE e.organization_id = :orgId
                   AND e.status <> 'RESOLVED'
            """;

    private static final String TYPE_FILTER = "       AND e.type IN (:types)\n";
    private static final String ASSIGNEE_FILTER = "       AND e.assigned_to = :assignee\n";
    private static final String UNASSIGNED_FILTER = "       AND e.assigned_to IS NULL\n";
    private static final String CURRENCY_FILTER = "       AND e.currency = :currency\n";

    // level (the GROUPING bitmask over assignee and name): 0 = per assignee, 3 = the bucket total.
    private static final String AGGREGATE = """
            )
            SELECT a.bucket, a.currency, a.assigned_to, u.display_name,
                   GROUPING(a.assigned_to, u.display_name) AS level,
                   count(*)                                AS cases,
                   coalesce(sum(a.amount), 0.0000)         AS amount_at_stake
              FROM active a
              LEFT JOIN app_user u ON u.id = a.assigned_to
             GROUP BY GROUPING SETS ((a.bucket, a.currency, a.assigned_to, u.display_name), (a.bucket, a.currency))
             ORDER BY level DESC, a.bucket, a.currency, a.assigned_to NULLS FIRST
            """;

    private final JdbcClient jdbc;

    ExceptionAgeingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** How the cases are narrowed; every value is bound, never concatenated. */
    public record Filter(
            List<String> types,
            @Nullable UUID assignee,
            boolean unassigned,
            @Nullable String currency) {}

    /** One grouping-sets row: {@code level} 0 is one assignee's cell, 3 a bucket's total. */
    public record Row(
            int bucket,
            String currency,
            @Nullable UUID assignedTo,
            @Nullable String displayName,
            int level,
            long cases,
            BigDecimal amountAtStake) {}

    public Instant now() {
        return jdbc.sql("SELECT now()").query(java.sql.Timestamp.class).single().toInstant();
    }

    /** The statement for a filter's shape: fixed fragments only, every value bound. */
    public static String sql(Filter filter) {
        StringBuilder sql = new StringBuilder(SELECT_ACTIVE);
        if (!filter.types().isEmpty()) {
            sql.append(TYPE_FILTER);
        }
        if (filter.assignee() != null) {
            sql.append(ASSIGNEE_FILTER);
        } else if (filter.unassigned()) {
            sql.append(UNASSIGNED_FILTER);
        }
        if (filter.currency() != null) {
            sql.append(CURRENCY_FILTER);
        }
        return sql.append(AGGREGATE).toString();
    }

    public List<Row> ageing(UUID orgId, Filter filter) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql(filter)).param("orgId", orgId);
        if (!filter.types().isEmpty()) {
            spec = spec.param("types", filter.types());
        }
        if (filter.assignee() != null) {
            spec = spec.param("assignee", filter.assignee());
        }
        if (filter.currency() != null) {
            spec = spec.param("currency", filter.currency());
        }
        return spec.query((rs, n) -> new Row(
                        rs.getInt("bucket"),
                        rs.getString("currency"),
                        rs.getObject("assigned_to", UUID.class),
                        rs.getString("display_name"),
                        rs.getInt("level"),
                        rs.getLong("cases"),
                        rs.getBigDecimal("amount_at_stake")))
                .list();
    }
}
