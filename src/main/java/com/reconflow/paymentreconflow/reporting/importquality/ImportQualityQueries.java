package com.reconflow.paymentreconflow.reporting.importquality;

import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport.Granularity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

    // level: 0 = bucket and code, 1 = the bucket's total over every code.
    private static final String ERRORS_OVER_TIME_SELECT = """
            WITH errs AS (
                SELECT %s AS bucket, e.error_code, e.batch_id, e.row_number
            """.formatted(BUCKET);

    private static final String ERRORS_OVER_TIME_FROM = """
              FROM import_batch b
              JOIN import_row_error e ON e.batch_id = b.id AND e.organization_id = b.organization_id
            """;

    private static final String ERRORS_OVER_TIME_GROUP = """
            )
            SELECT bucket, error_code,
                   GROUPING(error_code)                    AS level,
                   count(*)                                AS errors,
                   count(DISTINCT (batch_id, row_number))  AS error_rows
              FROM errs
             GROUP BY GROUPING SETS ((bucket, error_code), (bucket))
             ORDER BY bucket, level DESC, errors DESC, error_code
            """;

    // The denominator per bucket, from the batches' own counters, so a client can show a rate.
    private static final String DENOMINATOR_SELECT = """
            SELECT %s AS bucket, count(*) AS batches,
                   coalesce(sum(b.total_rows), 0) AS total_rows, coalesce(sum(b.error_rows), 0) AS error_rows
              FROM import_batch b
            """.formatted(BUCKET);

    private static final String DENOMINATOR_GROUP = " GROUP BY 1 ORDER BY 1\n";

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
        String sql = ERRORS_OVER_TIME_SELECT
                + ERRORS_OVER_TIME_FROM
                + IN_RANGE
                + (sourceType == null ? "" : SOURCE_FILTER)
                + ERRORS_OVER_TIME_GROUP;
        return overTime(sql, orgId, from, to, granularity, sourceType)
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
