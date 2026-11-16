package com.reconflow.paymentreconflow.ledger.internal;

import com.reconflow.paymentreconflow.ledger.RecordArchive;
import com.reconflow.paymentreconflow.shared.storage.CsvArchive;
import com.reconflow.paymentreconflow.shared.storage.CsvArchive.ArchivedFile;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcRecordArchive implements RecordArchive {

    // Through the parent, with the organization filter and RLS: the partition is pruned by the date
    // range, and no other tenant's row can reach the file.
    private static final String EXPORT = """
            SELECT id, organization_id, batch_id, source_type, source_row_number, raw_reference,
                   normalized_reference, amount, currency, transaction_type, status, transaction_at,
                   transaction_date, is_duplicate_in_source, raw_values, created_at
              FROM transaction_record
             WHERE organization_id = ? AND transaction_date >= ? AND transaction_date < ?
             ORDER BY id""";

    private final JdbcTemplate jdbc;
    private final PartitionManager partitions;
    private final CsvArchive archive;

    JdbcRecordArchive(JdbcTemplate jdbc, PartitionManager partitions, CsvArchive archive) {
        this.jdbc = jdbc;
        this.partitions = partitions;
        this.archive = archive;
    }

    @Override
    public List<YearMonth> partitionedMonths() {
        return partitions.existingMonths();
    }

    @Override
    public List<UUID> organizationsIn(YearMonth month) {
        // A cross-tenant list of ids for the system job, nothing else. Through a SECURITY DEFINER
        // function (V10): the app role has no rights on a partition itself, because a partition
        // has no RLS policy of its own (only the parent has, V4).
        return jdbc.queryForList("SELECT transaction_record_partition_organizations(?)", UUID.class, month.atDay(1));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Figures figures(UUID organizationId, YearMonth month) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) AS n, coalesce(sum(amount), 0) AS total FROM transaction_record"
                        + " WHERE organization_id = ? AND transaction_date >= ? AND transaction_date < ?",
                organizationId,
                month.atDay(1),
                month.plusMonths(1).atDay(1));
        return new Figures(((Number) row.get("n")).longValue(), (BigDecimal) row.get("total"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ArchivedFile export(UUID organizationId, YearMonth month, String key) {
        return archive.export(
                key,
                EXPORT,
                new Object[] {
                    organizationId, month.atDay(1), month.plusMonths(1).atDay(1)
                },
                "amount");
    }

    @Override
    public void dropPartition(YearMonth month) {
        partitions.drop(month);
    }
}
