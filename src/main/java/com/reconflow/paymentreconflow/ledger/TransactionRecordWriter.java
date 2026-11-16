package com.reconflow.paymentreconflow.ledger;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Ledger's write API for ingestion (M5.3). Bulk, JDBC-based, and in the <em>caller's</em>
 * transaction, so a chunk's records, its row errors and the batch checkpoint commit atomically.
 * Ledger owns {@code transaction_record}; ingestion never writes it directly.
 */
public interface TransactionRecordWriter {

    /**
     * Creates any missing monthly partitions, each in its own short transaction. Call it
     * <em>before</em> opening a chunk's transaction: {@code CREATE TABLE ... PARTITION OF} locks the
     * parent table, and holding that for a whole chunk would block every reader of every tenant.
     */
    void ensurePartitions(Collection<YearMonth> months);

    /**
     * Inserts the rows; a row whose {@code (batch_id, source_row_number, transaction_date)} already
     * exists is skipped ({@code ON CONFLICT DO NOTHING}), which absorbs any overlap when a crashed
     * import is resumed. Returns how many were actually inserted. Requires a transaction.
     */
    int insert(UUID organizationId, List<NewTransactionRecord> rows);

    /**
     * Rule D1: flags every record of the batch that shares (reference, type, amount, currency) with
     * another record of the same batch. Set-based SQL after all chunks are in, so nothing in the JVM
     * has to remember every row of the file. Returns the number of flagged records.
     */
    int markDuplicatesInBatch(UUID organizationId, UUID batchId);

    long countBatch(UUID organizationId, UUID batchId);

    /** Deletes every record of the batch; only for an explicit retry of a FAILED batch (M5.20). */
    int deleteBatch(UUID organizationId, UUID batchId);
}
