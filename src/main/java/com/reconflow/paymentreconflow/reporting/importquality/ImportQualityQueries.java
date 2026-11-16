package com.reconflow.paymentreconflow.reporting.importquality;

import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Granularity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every SQL statement of the import-quality report (M8.2): read-model SQL over {@code ingestion}'s
 * tables (phase-M8 §2.4), aggregated in the database.
 *
 * <p>{@code GROUPING SETS} computes the detail rows and their subtotals in one pass over the
 * errors, instead of one query per level; {@code GROUPING(...)} says which level a row belongs to.
 * {@code count(*)} counts errors, {@code count(DISTINCT row_number)} error rows: a row with two bad
 * columns is two errors but one error row.
 */
@Component
@Transactional(readOnly = true)
public class ImportQualityQueries {

    private static final String BATCH = """
            SELECT b.id, b.source_type, b.status, b.file_name, b.created_at, b.total_rows, b.valid_rows, b.error_rows
              FROM import_batch b
             WHERE b.organization_id = :orgId AND b.id = :batchId
            """;

    // level (the GROUPING bitmask): 0 = code and column, 1 = code only, 3 = the batch total.
    // Served by the UNIQUE (batch_id, row_number, column_name, error_code) index, which leads with batch_id.
    private static final String BATCH_ERRORS = """
            SELECT e.error_code, e.column_name,
                   GROUPING(e.error_code, e.column_name) AS level,
                   count(*)                              AS errors,
                   count(DISTINCT e.row_number)          AS error_rows
              FROM import_row_error e
             WHERE e.organization_id = :orgId AND e.batch_id = :batchId
             GROUP BY GROUPING SETS ((e.error_code, e.column_name), (e.error_code), ())
             ORDER BY level DESC, errors DESC, e.error_code, e.column_name NULLS FIRST
            """;

    /*
     * Over time: buckets by when the batch arrived, in UTC whatever the session's time zone, over
     * a half-open range [from 00:00Z, to+1 00:00Z), so no instant is in two buckets or none.
     * Batches are found through import_batch_list_idx (organization_id, created_at DESC), their
     * errors through the batch_id-led unique index. The optional source filter is a second query
     * text, not "(:type IS NULL OR source_type = :type)", which can get a generic plan that
     * ignores the filter.
     */
    private static final String IN_RANGE = """
             WHERE b.organization_id = :orgId
               AND b.created_at >= :fromInclusive
               AND b.created_at <  :toExclusive
            """;

    private static final String SOURCE_FILTER = "   AND b.source_type = :sourceType\n";

    private static final String BUCKET = "date_trunc(:granularity, b.created_at AT TIME ZONE 'UTC')::date";

    /*
     * Errors per bucket and code, and per bucket over every code (level 1). Counted per batch in a
     * LATERAL subquery, then summed per bucket: the subquery aggregates, so PostgreSQL can't flatten
     * it into a join, and each batch's errors are one range of the batch_id-led unique index. As a
     * plain join, a month of one tenant's errors made the planner hash-join a sequential scan of
     * the whole import_row_error table (M8.5, docs/benchmarks.md). Summing per-batch distinct row
     * counts is exact: a row belongs to exactly one batch.
     */
    private static final String ERRORS_OVER_TIME_SELECT = """
            SELECT bucket, error_code, level, sum(errors) AS errors, sum(error_rows) AS error_rows
              FROM (
                SELECT %s AS bucket, pb.error_code, pb.level, pb.errors, pb.error_rows
                  FROM import_batch b
                 CROSS JOIN LATERAL (
                       SELECT e.error_code,
                              GROUPING(e.error_code)       AS level,
                              count(*)                     AS errors,
                              count(DISTINCT e.row_number) AS error_rows
                         FROM import_row_error e
                        WHERE e.batch_id = b.id AND e.organization_id = b.organization_id
                        GROUP BY GROUPING SETS ((e.error_code), ())
                 ) pb
            """.formatted(BUCKET);

    private static final String ERRORS_OVER_TIME_GROUP = """
              ) per_batch
             GROUP BY bucket, level, error_code
             ORDER BY bucket, level DESC, errors DESC, error_code
            """;

    // The denominator per bucket, from the batches' own counters, so a client can show a rate.
    private static final String DENOMINATOR_SELECT = """
            SELECT %s AS bucket, count(*) AS batches,
                   coalesce(sum(b.total_rows), 0) AS total_rows, coalesce(sum(b.error_rows), 0) AS error_rows
              FROM import_batch b
            """.formatted(BUCKET);

    private static final String DENOMINATOR_GROUP = " GROUP BY 1 ORDER BY 1\n";

    /** Every statement this class runs, by name, for M8.5's plan check (see ReconciliationReportQueries). */
    public static final Map<String, String> STATEMENTS = Map.of(
            "importQuality.batch", BATCH,
            "importQuality.batch.errors", BATCH_ERRORS,
            "importQuality.overTime.errors", errorsOverTimeSql(false),
            "importQuality.overTime.errors.bySource", errorsOverTimeSql(true),
            "importQuality.overTime.denominators", denominatorsSql(false),
            "importQuality.overTime.denominators.bySource", denominatorsSql(true));

    private final JdbcClient jdbc;

    ImportQualityQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record BatchRow(
            UUID id,
            String sourceType,
            String status,
            String fileName,
            Instant createdAt,
            long totalRows,
            long validRows,
            long errorRows) {}

    /** One grouping-sets row: {@code level} 0 is the detail, higher levels are subtotals. */
    public record ErrorRow(
            @Nullable LocalDate bucket,
            @Nullable String errorCode,
            @Nullable String column,
            int level,
            long errors,
            long errorRows) {}

    public record DenominatorRow(LocalDate bucket, long batches, long totalRows, long errorRows) {}

    public Optional<BatchRow> batch(UUID orgId, UUID batchId) {
        return jdbc.sql(BATCH)
                .param("orgId", orgId)
                .param("batchId", batchId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_type"),
                        rs.getString("status"),
                        rs.getString("file_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("total_rows"),
                        rs.getLong("valid_rows"),
                        rs.getLong("error_rows")))
                .optional();
    }

    public List<ErrorRow> batchErrors(UUID orgId, UUID batchId) {
        return jdbc.sql(BATCH_ERRORS)
                .param("orgId", orgId)
                .param("batchId", batchId)
                .query((rs, n) -> new ErrorRow(
                        null,
                        rs.getString("error_code"),
                        rs.getString("column_name"),
                        rs.getInt("level"),
                        rs.getLong("errors"),
                        rs.getLong("error_rows")))
                .list();
    }

    public List<ErrorRow> errorsOverTime(
            UUID orgId, LocalDate from, LocalDate to, Granularity granularity, @Nullable String sourceType) {
        return overTime(errorsOverTimeSql(sourceType != null), orgId, from, to, granularity, sourceType)
                .query((rs, n) -> new ErrorRow(
                        rs.getObject("bucket", LocalDate.class),
                        rs.getString("error_code"),
                        null,
                        rs.getInt("level"),
                        rs.getLong("errors"),
                        rs.getLong("error_rows")))
                .list();
    }

    public List<DenominatorRow> denominators(
            UUID orgId, LocalDate from, LocalDate to, Granularity granularity, @Nullable String sourceType) {
        String sql = DENOMINATOR_SELECT + IN_RANGE + (sourceType == null ? "" : SOURCE_FILTER) + DENOMINATOR_GROUP;
        return overTime(sql, orgId, from, to, granularity, sourceType)
                .query((rs, n) -> new DenominatorRow(
                        rs.getObject("bucket", LocalDate.class),
                        rs.getLong("batches"),
                        rs.getLong("total_rows"),
                        rs.getLong("error_rows")))
                .list();
    }

    private static String errorsOverTimeSql(boolean bySource) {
        return ERRORS_OVER_TIME_SELECT + IN_RANGE + (bySource ? SOURCE_FILTER : "") + ERRORS_OVER_TIME_GROUP;
    }

    private static String denominatorsSql(boolean bySource) {
        return DENOMINATOR_SELECT + IN_RANGE + (bySource ? SOURCE_FILTER : "") + DENOMINATOR_GROUP;
    }

    private JdbcClient.StatementSpec overTime(
            String sql,
            UUID orgId,
            LocalDate from,
            LocalDate to,
            Granularity granularity,
            @Nullable String sourceType) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("orgId", orgId)
                .param("granularity", granularity.sqlField())
                .param("fromInclusive", OffsetDateTime.of(from.atStartOfDay(), ZoneOffset.UTC))
                .param("toExclusive", OffsetDateTime.of(to.plusDays(1).atStartOfDay(), ZoneOffset.UTC));
        return sourceType == null ? spec : spec.param("sourceType", sourceType);
    }
}
