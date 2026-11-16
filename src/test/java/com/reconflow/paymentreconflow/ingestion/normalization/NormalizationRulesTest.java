package com.reconflow.paymentreconflow.ingestion.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ingestion.validation.ValidatedRow;
import com.reconflow.paymentreconflow.ledger.NewTransactionRecord;
import com.reconflow.paymentreconflow.ledger.ReferenceNormalizer;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.ledger.TransactionStatus;
import com.reconflow.paymentreconflow.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** M5.21: one test per normalization rule N1–N11 of reconciliation-rules.md §1, named after it. */
class NormalizationRulesTest {

    static final Normalizer NORMALIZER = new Normalizer();

    @Test
    void n1_strips_leading_and_trailing_whitespace_including_unicode_spaces() {
        assertThat(ReferenceNormalizer.normalize(" pay-106 ")).isEqualTo("PAY-106");
        assertThat(ReferenceNormalizer.normalize(" PAY-106 \t")).isEqualTo("PAY-106");
    }

    @Test
    void n2_applies_nfkc_to_full_width_characters() {
        assertThat(ReferenceNormalizer.normalize("ＰＡＹ－１０６")).isEqualTo("PAY-106");
    }

    @Test
    void n2_turns_a_non_breaking_space_inside_a_reference_into_a_plain_space() {
        assertThat(ReferenceNormalizer.normalize("PAY 106")).isEqualTo("PAY 106");
    }

    @Test
    void n3_uppercases_with_locale_root_even_on_a_turkish_jvm() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            // With the default locale, "i" would become the dotted İ (U+0130).
            assertThat(ReferenceNormalizer.normalize("pay-101i")).isEqualTo("PAY-101I");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @CsvSource({"PAY-101,PAY-101", "PAY101,PAY101", "PAY 101,PAY 101", "007-PAY,007-PAY", "pay_101,PAY_101"})
    void n4_keeps_internal_characters(String raw, String expected) {
        assertThat(ReferenceNormalizer.normalize(raw)).isEqualTo(expected);
        assertThat(ReferenceNormalizer.normalize("PAY-101")).isNotEqualTo(ReferenceNormalizer.normalize("PAY101"));
    }

    @ParameterizedTest
    @CsvSource({"aed,AED", " usd ,USD", "Eur,EUR"})
    void n5_trims_and_uppercases_the_currency(String raw, String expected) {
        assertThat(normalize(row().currency(raw)).currency()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"120.5,120.5000", "1000,1000.0000", "300.0,300.0000", "0.0001,0.0001", "+15,15.0000"})
    void n6_stores_the_amount_at_scale_four_by_value(String raw, String expected) {
        BigDecimal amount = normalize(row().amount(new BigDecimal(raw))).amount();
        assertThat(amount).isEqualTo(new BigDecimal(expected)); // equals: value AND scale 4
        assertThat(amount.scale()).isEqualTo(4);
    }

    @Test
    void n7_stores_the_timestamp_as_a_utc_instant_and_dates_the_record_by_its_utc_date() {
        NewTransactionRecord record = normalize(row().at("2026-10-01T02:00+04:00"));

        assertThat(record.transactionAt()).isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
        assertThat(record.transactionDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void n7_converts_the_sample_pay_202_case_to_the_gateways_date() {
        // ledger-currency-mismatch.csv: 02:30 on 4 October in Dubai is 22:30 UTC on 3 October.
        assertThat(normalize(row().at("2026-10-04T02:30:00+04:00")).transactionDate())
                .isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void n8_uses_a_date_only_ledger_date_as_it_is() {
        NewTransactionRecord record = normalize(row().date(LocalDate.of(2026, 10, 5)));

        assertThat(record.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(record.transactionAt()).isNull();
    }

    @Test
    void n8_lets_the_timestamp_win_when_both_are_given() {
        NewTransactionRecord record =
                normalize(row().at("2026-10-01T09:15:00+04:00").date(LocalDate.of(2026, 12, 31)));

        assertThat(record.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @ParameterizedTest
    @CsvSource({
        "success,SUCCESS",
        "Succeeded,SUCCESS",
        "CAPTURED,SUCCESS",
        "settled,SUCCESS",
        "paid,SUCCESS",
        "completed,SUCCESS",
        "pending,PENDING",
        "processing,PENDING",
        "authorized,PENDING",
        "failed,FAILED",
        "declined,FAILED",
        "error,FAILED",
        "reversed,REVERSED",
        "refunded,REVERSED",
        "voided,REVERSED",
        "' cancelled ',REVERSED"
    })
    void n9_maps_every_status_synonym_case_insensitively(String raw, TransactionStatus expected) {
        assertThat(StatusMapping.map(raw)).contains(expected);
        assertThat(normalize(row().status(raw)).status()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sale", "ON_HOLD", ""})
    void n9_knows_nothing_outside_the_table(String raw) {
        assertThat(StatusMapping.map(raw)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"refund,REFUND", "' PAYMENT ',PAYMENT", "Chargeback,CHARGEBACK", "fee,FEE", "'',PAYMENT"})
    void n10_uppercases_the_type_and_defaults_to_payment(String raw, TransactionType expected) {
        assertThat(normalize(row().type(raw)).transactionType()).isEqualTo(expected);
        assertThat(normalize(row().type(null)).transactionType()).isEqualTo(TransactionType.PAYMENT);
    }

    @Test
    void n11_keeps_the_raw_reference_untrimmed_and_every_original_column() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(" Reference ", " pay-106 ");
        raw.put("AMOUNT", "120.5");
        raw.put("order_id", "O-9");

        NewTransactionRecord record = normalize(row().reference(" pay-106 ").raw(raw));

        assertThat(record.rawReference()).isEqualTo(" pay-106 ");
        assertThat(record.rawValues()).isEqualTo(raw);
    }

    private static NewTransactionRecord normalize(RowBuilder b) {
        return NORMALIZER.normalize(b.build(), UUID.randomUUID(), SourceType.INTERNAL_LEDGER);
    }

    static RowBuilder row() {
        return new RowBuilder();
    }

    static final class RowBuilder {
        String reference = "PAY-101";
        BigDecimal amount = new BigDecimal("100.00");
        String currency = "AED";
        String status = "SUCCESS";
        String type = "PAYMENT";
        OffsetDateTime at;
        LocalDate date = LocalDate.of(2026, 10, 5);
        Map<String, String> raw = Map.of();

        RowBuilder reference(String v) {
            reference = v;
            return this;
        }

        RowBuilder amount(BigDecimal v) {
            amount = v;
            return this;
        }

        RowBuilder currency(String v) {
            currency = v;
            return this;
        }

        RowBuilder status(String v) {
            status = v;
            return this;
        }

        RowBuilder type(String v) {
            type = v;
            return this;
        }

        RowBuilder at(String v) {
            at = OffsetDateTime.parse(v);
            return this;
        }

        RowBuilder date(LocalDate v) {
            date = v;
            return this;
        }

        RowBuilder raw(Map<String, String> v) {
            raw = v;
            return this;
        }

        ValidatedRow build() {
            return new ValidatedRow(1, reference, amount, currency, status, type, at, date, raw);
        }
    }
}
