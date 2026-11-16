package com.reconflow.paymentreconflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ledger.internal.TransactionRecord;
import com.reconflow.paymentreconflow.ledger.internal.TransactionRecordId;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M5.3: rows land in their monthly partitions (missing ones are created first) and never in the
 * default.
 */
class TransactionRecordWriterIT extends AbstractIntegrationTest {

    @Autowired
    TransactionRecordWriter writer;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    EntityManager em;

    @Test
    void rowsLandInTheirMonthlyPartitionsWhichAreCreatedFirst() {
        UUID org = data().newOrganization();
        UUID batch = batch(org);
        // March and April 2025 are older than V1's initial window (12 months back from when the
        // test database was migrated), so their partitions don't exist yet.
        List<LocalDate> dates = List.of(
                LocalDate.of(2025, 3, 1),
                LocalDate.of(2025, 3, 31),
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 3),
                LocalDate.of(2026, 10, 4),
                LocalDate.of(2026, 10, 5),
                LocalDate.of(2025, 4, 1),
                LocalDate.of(2025, 3, 2));
        List<NewTransactionRecord> rows = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            rows.add(record(batch, i + 1, "PAY-" + i, "10.0000", dates.get(i)));
        }

        writer.ensurePartitions(Set.of(YearMonth.of(2025, 3), YearMonth.of(2025, 4), YearMonth.of(2026, 10)));
        int inserted = tx.execute(s -> writer.insert(org, rows));

        assertThat(inserted).isEqualTo(10);
        List<Map<String, Object>> placement = jdbc.queryForList("""
                SELECT tableoid::regclass::text AS partition, count(*) AS n
                  FROM transaction_record WHERE batch_id = ? GROUP BY 1 ORDER BY 1""", batch);
        assertThat(placement)
                .extracting(r -> r.get("partition") + "=" + r.get("n"))
                .containsExactly(
                        "transaction_record_2025_03=3", "transaction_record_2025_04=2", "transaction_record_2026_10=5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_record_default", Long.class))
                .isZero();
    }

    @Test
    void theReadSideEntityMapsTheCompositeKeyAndRawValues() {
        UUID org = data().newOrganization();
        UUID batch = batch(org);
        NewTransactionRecord row = record(batch, 1, "PAY-106", "120.5000", LocalDate.of(2026, 10, 2));
        tx.executeWithoutResult(s -> writer.insert(org, List.of(row)));

        TransactionRecord loaded = tx.execute(
                s -> em.find(TransactionRecord.class, new TransactionRecordId(row.id(), row.transactionDate())));

        assertThat(loaded.getAmount()).isEqualByComparingTo("120.5");
        assertThat(loaded.getAmount().scale()).isEqualTo(4);
        assertThat(loaded.getCurrency()).isEqualTo("AED");
        assertThat(loaded.getRawValues()).containsEntry("reference", " pay-106 ");
    }

    private UUID batch(UUID org) {
        UUID user = data().newMember(org, Role.UPLOADER).id();
        return data().newImportBatch(org, user, "GATEWAY", "PROCESSING");
    }

    private static NewTransactionRecord record(UUID batch, int row, String ref, String amount, LocalDate date) {
        return new NewTransactionRecord(
                Ids.newId(),
                batch,
                SourceType.GATEWAY,
                row,
                " " + ref.toLowerCase() + " ",
                ref,
                new BigDecimal(amount),
                "AED",
                TransactionType.PAYMENT,
                TransactionStatus.SUCCESS,
                date,
                date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plusSeconds(3600),
                Map.of("reference", " " + ref.toLowerCase() + " ", "amount", amount));
    }
}
