package com.reconflow.paymentreconflow.ingestion.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ingestion.validation.ValidatedRow;
import com.reconflow.paymentreconflow.ledger.NewTransactionRecord;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.ledger.TransactionStatus;
import com.reconflow.paymentreconflow.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M5.12: the normalizer turns a valid row into a record; per-rule cases are in NormalizationRulesTest. */
class NormalizerTest {

    static final UUID BATCH = UUID.randomUUID();

    @Test
    void normalizesTheSampleLedgerRowSix() {
        Map<String, String> raw = Map.of("reference", " pay-106 ", "amount", "120.5", "currency", "aed");
        ValidatedRow row = new ValidatedRow(
                6,
                " pay-106 ",
                new BigDecimal("120.5"),
                "aed",
                "SUCCESS",
                "PAYMENT",
                OffsetDateTime.parse("2026-10-02T13:00:00+04:00"),
                null,
                raw);

        NewTransactionRecord record = new Normalizer().normalize(row, BATCH, SourceType.INTERNAL_LEDGER);

        assertThat(record.rawReference()).isEqualTo(" pay-106 ");
        assertThat(record.normalizedReference()).isEqualTo("PAY-106");
        assertThat(record.amount()).isEqualTo(new BigDecimal("120.5000"));
        assertThat(record.currency()).isEqualTo("AED");
        assertThat(record.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(record.transactionType()).isEqualTo(TransactionType.PAYMENT);
        assertThat(record.transactionAt()).isEqualTo(Instant.parse("2026-10-02T09:00:00Z"));
        assertThat(record.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(record.sourceRowNumber()).isEqualTo(6);
        assertThat(record.batchId()).isEqualTo(BATCH);
        assertThat(record.rawValues()).isEqualTo(raw);
    }

    @Test
    void aDateOnlyLedgerRowKeepsItsDateAndHasNoTimestamp() {
        ValidatedRow row = new ValidatedRow(
                1,
                "PAY-401",
                new BigDecimal("100.00"),
                "AED",
                "SUCCESS",
                null,
                null,
                LocalDate.of(2026, 10, 5),
                Map.of());

        NewTransactionRecord record = new Normalizer().normalize(row, BATCH, SourceType.INTERNAL_LEDGER);

        assertThat(record.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(record.transactionAt()).isNull();
        assertThat(record.transactionType()).isEqualTo(TransactionType.PAYMENT);
    }
}
