package com.reconflow.paymentreconflow.ingestion.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ingestion.application.ImportProperties;
import com.reconflow.paymentreconflow.ingestion.csv.Field;
import com.reconflow.paymentreconflow.ingestion.csv.ParsedRow;
import com.reconflow.paymentreconflow.ledger.SourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** M5.11: the validator collects every error of a row and keeps going. */
class RowValidatorTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);
    static final RowValidator VALIDATOR = new RowValidator(
            new ImportProperties(500_000, 5000, 10_000, Period.ofYears(2), Period.ofDays(31), DataSize.ofMegabytes(50)),
            CLOCK);

    @Test
    void aValidLedgerRowPassesWithItsParsedValues() {
        RowValidation result = VALIDATOR.validate(
                ledger("PAY-101", "1000.00", "AED", "SUCCESS", "2026-10-01"), SourceType.INTERNAL_LEDGER);

        assertThat(result.isValid()).isTrue();
        assertThat(result.valid().amount()).isEqualByComparingTo("1000");
        assertThat(result.valid().transactionDate()).hasToString("2026-10-01");
    }

    @Test
    void aRowWithThreeBadFieldsGetsThreeErrors() {
        RowValidation result = VALIDATOR.validate(
                ledger("PAY-1", "1,000.00", "XYZ", "SUCCESS", "01/10/2026"), SourceType.INTERNAL_LEDGER);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::column, FieldError::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("amount", RowErrorCode.INVALID_AMOUNT),
                        org.assertj.core.groups.Tuple.tuple("currency", RowErrorCode.INVALID_CURRENCY),
                        org.assertj.core.groups.Tuple.tuple("transaction_date", RowErrorCode.INVALID_DATE));
    }

    @Test
    void aMalformedRowGetsOnlyMalformedRow() {
        ParsedRow malformed = new ParsedRow(9, Map.of(), Map.of(), "PAY-409,\"60.00", "Quoted field is never closed");

        RowValidation result = VALIDATOR.validate(malformed, SourceType.INTERNAL_LEDGER);

        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(RowErrorCode.MALFORMED_ROW);
            assertThat(e.column()).isNull();
        });
    }

    static ParsedRow ledger(String reference, String amount, String currency, String status, String date) {
        Map<Field, String> fields = new EnumMap<>(Field.class);
        fields.put(Field.REFERENCE, reference);
        fields.put(Field.AMOUNT, amount);
        fields.put(Field.CURRENCY, currency);
        fields.put(Field.STATUS, status);
        fields.put(Field.TRANSACTION_DATE, date);
        return new ParsedRow(1, fields, Map.of(), "raw", null);
    }
}
