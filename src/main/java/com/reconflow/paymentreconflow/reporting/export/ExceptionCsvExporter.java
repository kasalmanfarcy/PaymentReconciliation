package com.reconflow.paymentreconflow.reporting.export;

import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a run's exceptions as CSV (M8.4), one row per case with its ledger and gateway records side
 * by side, its status, assignee and resolution. Used by both paths: streamed into the HTTP response,
 * and written to a temporary file by the EXPORT job.
 *
 * <p>Memory stays flat whatever the row count, because both ends stream:
 * <ul>
 *   <li><b>From the database</b>: pgjdbc uses a server-side cursor, fetching {@value #FETCH_SIZE}
 *       rows at a time, only when autocommit is off <em>and</em> a fetch size is set; otherwise it
 *       reads every row into memory before returning the first. Hence the transaction and
 *       {@code withFetchSize}.</li>
 *   <li><b>To the client</b>: a {@link RowCallbackHandler} writes each row as it arrives into a
 *       buffered writer over the output stream; nothing collects the rows in a list.</li>
 * </ul>
 *
 * <p>The tenant is passed in, never read from TenantContext: on the streaming path this runs on
 * an MVC async thread after the controller returned, where the request's binding is gone. It is
 * bound again here, <em>before</em> the transaction begins, so row-level security sees it.
 */
@Component
public class ExceptionCsvExporter {

    static final int FETCH_SIZE = 1000;

    static final String[] HEADERS = {
        "case_id",
        "type",
        "reason_code",
        "status",
        "discrepancy_key",
        "currency",
        "amount",
        "amount_difference",
        "ledger_references",
        "ledger_amounts",
        "ledger_dates",
        "gateway_references",
        "gateway_amounts",
        "gateway_dates",
        "assignee",
        "resolution_code",
        "resolution_note",
        "resolved_by",
        "resolved_at",
        "created_at"
    };

    /**
     * The cases the run observed (exception_case_run, as GET /exceptions?runId= and the run summary
     * count them). Each case's records are aggregated in a LATERAL subquery joined on
     * (id, transaction_date), so each record lookup prunes to one partition. Ordered by the case id:
     * UUIDv7 ids sort by creation time, and exception_case_run's primary key (run_id, exception_id)
     * already returns them in that order, so there is no sort to wait for before the first row.
     */
    public static final String EXPORT = """
            SELECT e.id, e.type, e.reason_code, e.status, e.discrepancy_key, e.currency::text AS currency,
                   e.amount, e.amount_difference,
                   rec.ledger_references, rec.ledger_amounts, rec.ledger_dates,
                   rec.gateway_references, rec.gateway_amounts, rec.gateway_dates,
                   au.display_name AS assignee, e.resolution_code, e.resolution_note,
                   ru.display_name AS resolved_by, e.resolved_at, e.created_at
              FROM exception_case_run cr
              JOIN exception_case e ON e.id = cr.exception_id AND e.organization_id = cr.organization_id
              LEFT JOIN app_user au ON au.id = e.assigned_to
              LEFT JOIN app_user ru ON ru.id = e.resolved_by
              LEFT JOIN LATERAL (
                  SELECT string_agg(t.raw_reference, ' | ' ORDER BY t.transaction_date, t.id)
                             FILTER (WHERE t.source_type = 'INTERNAL_LEDGER')                      AS ledger_references,
                         string_agg(t.amount::text, ' | ' ORDER BY t.transaction_date, t.id)
                             FILTER (WHERE t.source_type = 'INTERNAL_LEDGER')                      AS ledger_amounts,
                         string_agg(t.transaction_date::text, ' | ' ORDER BY t.transaction_date, t.id)
                             FILTER (WHERE t.source_type = 'INTERNAL_LEDGER')                      AS ledger_dates,
                         string_agg(t.raw_reference, ' | ' ORDER BY t.transaction_date, t.id)
                             FILTER (WHERE t.source_type = 'GATEWAY')                              AS gateway_references,
                         string_agg(t.amount::text, ' | ' ORDER BY t.transaction_date, t.id)
                             FILTER (WHERE t.source_type = 'GATEWAY')                              AS gateway_amounts,
                         string_agg(t.transaction_date::text, ' | ' ORDER BY t.transaction_date, t.id)
                             FILTER (WHERE t.source_type = 'GATEWAY')                              AS gateway_dates
                    FROM exception_case_record r
                    JOIN transaction_record t
                      ON t.id = r.transaction_record_id
                     AND t.transaction_date = r.transaction_date
                     AND t.organization_id = r.organization_id
                   WHERE r.exception_id = e.id AND r.organization_id = e.organization_id
              ) rec ON true
             WHERE cr.organization_id = :orgId AND cr.run_id = :runId
             ORDER BY cr.exception_id
            """;

    private final JdbcClient jdbc;
    private final TenantScope tenantScope;
    private final TransactionTemplate readOnly;

    ExceptionCsvExporter(JdbcClient jdbc, TenantScope tenantScope, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Writes the CSV (UTF-8 with a BOM, CRLF rows, a header row) and returns how many cases it wrote. */
    public long write(UUID organizationId, UUID runId, OutputStream out) {
        Long rows = tenantScope.call(
                organizationId,
                () -> readOnly.execute(s -> {
                    Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                    try {
                        // A UTF-8 BOM, or Excel guesses the encoding and mangles non-ASCII names and notes.
                        writer.write('﻿');
                        CSVPrinter csv = CSVFormat.DEFAULT
                                .builder()
                                .setHeader(HEADERS)
                                .get()
                                .print(writer);
                        AtomicLong count = new AtomicLong();
                        jdbc.sql(EXPORT)
                                .param("orgId", organizationId)
                                .param("runId", runId)
                                .withFetchSize(FETCH_SIZE)
                                .query((RowCallbackHandler) rs -> {
                                    print(csv, rs);
                                    count.incrementAndGet();
                                });
                        csv.flush(); // flushes the writer; the caller owns (and closes) the stream
                        return count.get();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
        return rows == null ? 0 : rows;
    }

    private static void print(CSVPrinter csv, ResultSet rs) throws SQLException {
        try {
            csv.printRecord(
                    CsvCell.text(rs.getString("id")),
                    CsvCell.text(rs.getString("type")),
                    CsvCell.text(rs.getString("reason_code")),
                    CsvCell.text(rs.getString("status")),
                    CsvCell.text(rs.getString("discrepancy_key")),
                    CsvCell.text(rs.getString("currency")),
                    CsvCell.money(rs.getBigDecimal("amount")),
                    CsvCell.money(rs.getBigDecimal("amount_difference")),
                    CsvCell.text(rs.getString("ledger_references")),
                    CsvCell.text(rs.getString("ledger_amounts")),
                    CsvCell.text(rs.getString("ledger_dates")),
                    CsvCell.text(rs.getString("gateway_references")),
                    CsvCell.text(rs.getString("gateway_amounts")),
                    CsvCell.text(rs.getString("gateway_dates")),
                    CsvCell.text(rs.getString("assignee")),
                    CsvCell.text(rs.getString("resolution_code")),
                    CsvCell.text(rs.getString("resolution_note")),
                    CsvCell.text(rs.getString("resolved_by")),
                    CsvCell.timestamp(instant(rs, "resolved_at")),
                    CsvCell.timestamp(instant(rs, "created_at")));
        } catch (IOException e) {
            throw new UncheckedIOException(e); // e.g. the client went away mid-download
        }
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
