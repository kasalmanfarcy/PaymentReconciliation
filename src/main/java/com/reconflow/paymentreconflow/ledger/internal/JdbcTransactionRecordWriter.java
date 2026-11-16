package com.reconflow.paymentreconflow.ledger.internal;

import com.reconflow.paymentreconflow.ledger.NewTransactionRecord;
import com.reconflow.paymentreconflow.ledger.TransactionRecordWriter;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hot write path, in plain JDBC rather than {@code saveAll(entities)} (M5.3):
 *
 * <ul>
 *   <li>constant memory per chunk: a small record per row, no persistence-context snapshot;</li>
 *   <li>{@code ON CONFLICT DO NOTHING} and the {@code ::jsonb} cast are plain SQL;</li>
 *   <li>with {@code reWriteBatchedInserts=true}, pgjdbc turns each batch of 500 into a few
 *       multi-row INSERTs, roughly an order of magnitude faster than 500 single-row statements.</li>
 * </ul>
 *
 * {@code COPY} would be faster still but can't do ON CONFLICT; only worth it if M9.6's benchmark
 * says inserts are the bottleneck.
 */
@Component
class JdbcTransactionRecordWriter implements TransactionRecordWriter {

    static final int BATCH_SIZE = 500;

    private static final String INSERT = """
            INSERT INTO transaction_record
              (id, organization_id, batch_id, source_type, source_row_number, raw_reference,
               normalized_reference, amount, currency, transaction_type, status,
               transaction_date, transaction_at, raw_values)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (batch_id, source_row_number, transaction_date) DO NOTHING
            """;

    // Rule D1. NUMERIC compares by value in SQL, so 250.0000 = 250.0000 whatever the file wrote.
    // PostgreSQL groups with a hash or sort that spills to disk past work_mem: the JVM holds nothing.
    private static final String MARK_DUPLICATES = """
            UPDATE transaction_record t
               SET is_duplicate_in_source = true
             WHERE t.organization_id = ? AND t.batch_id = ?
               AND (t.normalized_reference, t.transaction_type, t.amount, t.currency) IN (
                     SELECT normalized_reference, transaction_type, amount, currency
                       FROM transaction_record
                      WHERE organization_id = ? AND batch_id = ?
                      GROUP BY normalized_reference, transaction_type, amount, currency
                     HAVING count(*) > 1)
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final PartitionManager partitions;

    JdbcTransactionRecordWriter(JdbcTemplate jdbc, JsonMapper json, PartitionManager partitions) {
        this.jdbc = jdbc;
        this.json = json;
        this.partitions = partitions;
    }

    @Override
    public void ensurePartitions(Collection<YearMonth> months) {
        partitions.ensure(months);
    }

    @Override
    public void refreshStatistics(Collection<YearMonth> months) {
        for (YearMonth month : new java.util.TreeSet<>(months)) {
            if (partitions.exists(month)) {
                // The name is built by PartitionManager from a YearMonth, never from user input.
                jdbc.execute("ANALYZE " + PartitionManager.partitionName(month));
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int insert(UUID organizationId, List<NewTransactionRecord> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[][] counts = jdbc.batchUpdate(INSERT, rows, BATCH_SIZE, (ps, r) -> {
            ps.setObject(1, r.id());
            ps.setObject(2, organizationId);
            ps.setObject(3, r.batchId());
            ps.setString(4, r.sourceType().name());
            ps.setInt(5, r.sourceRowNumber());
            ps.setString(6, r.rawReference());
            ps.setString(7, r.normalizedReference());
            ps.setBigDecimal(8, r.amount());
            ps.setString(9, r.currency());
            ps.setString(10, r.transactionType().name());
            ps.setString(11, r.status().name());
            ps.setObject(12, r.transactionDate()); // LocalDate -> DATE: a calendar date, no time zone
            ps.setObject(
                    13, r.transactionAt() == null ? null : r.transactionAt().atOffset(ZoneOffset.UTC));
            ps.setString(14, json.writeValueAsString(r.rawValues()));
        });
        // With reWriteBatchedInserts the driver may report SUCCESS_NO_INFO (-2) instead of counts.
        return Arrays.stream(counts)
                .flatMapToInt(Arrays::stream)
                .map(n -> n < 0 ? 1 : n)
                .sum();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int markDuplicatesInBatch(UUID organizationId, UUID batchId) {
        return jdbc.update(MARK_DUPLICATES, organizationId, batchId, organizationId, batchId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long countBatch(UUID organizationId, UUID batchId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM transaction_record WHERE organization_id = ? AND batch_id = ?",
                Long.class,
                organizationId,
                batchId);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteBatch(UUID organizationId, UUID batchId) {
        return jdbc.update(
                "DELETE FROM transaction_record WHERE organization_id = ? AND batch_id = ?", organizationId, batchId);
    }
}
