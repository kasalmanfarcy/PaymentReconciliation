package com.reconflow.paymentreconflow.ledger;

import com.reconflow.paymentreconflow.shared.storage.CsvArchive.ArchivedFile;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Ledger's side of M9.16's archival: which monthly partitions exist, whose records they hold, the
 * figures an archive must match, the export itself, and finally dropping the partition. Ledger owns
 * {@code transaction_record}; the orchestration (which also involves matches and case links) lives
 * in the module that may see all three.
 */
public interface RecordArchive {

    /** Row count and amount total of one organization's records in one month, from the database. */
    record Figures(long rows, BigDecimal amountSum) {}

    /** Months with a partition, oldest first. */
    List<YearMonth> partitionedMonths();

    /** Organizations with records in the month (read from the partition: ids only, cross-tenant). */
    List<UUID> organizationsIn(YearMonth month);

    /** In a transaction with the organization bound. */
    Figures figures(UUID organizationId, YearMonth month);

    /** In a transaction with the organization bound: every column, ordered by id. */
    ArchivedFile export(UUID organizationId, YearMonth month, String key);

    /** After every organization's records and dependents are archived: detach and drop. */
    void dropPartition(YearMonth month);
}
