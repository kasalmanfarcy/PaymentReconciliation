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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

/**
 * M5.21: one test per validation rule of requirements.md §7.1 and §7.5, named after it. Today is
 * fixed at 2026-11-16 (UTC), so the date window is 2024-11-16 .. 2026-12-17.
 */
class ValidationRulesTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);
    static final RowValidator VALIDATOR = new RowValidator(
            new ImportProperties(500_000, 5000, 10_000, Period.ofYears(2), Period.ofDays(31), DataSize.ofMegabytes(50)),
            CLOCK);

    // --- amounts: format, then positivity, then scale, then range; first failure only -------------

    @ParameterizedTest
    @ValueSource(
            strings = {"1000", "1000.5", "1000.1234", "+15.00", " 42.10 ", "0.0001", "999999999999999.9999", "007.50"})
    void accepts_plain_decimal_amounts(String amount) {
        assertThat(validate(row().with(Field.AMOUNT, amount)).isValid())
                .as(amount)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"1,000.00", "AED 10", "$5", "1e3", "1E+3", ".5", "5.", "abc", "1 000", "--5", "0x10", "١٢٣"})
    void rejects_amount_that_is_not_a_plain_decimal_as_INVALID_AMOUNT(String amount) {
        assertOnlyError(row().with(Field.AMOUNT, amount), "amount", RowErrorCode.INVALID_AMOUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "-0.01", "-10.12345"})
    void rejects_zero_or_negative_amount_as_AMOUNT_NOT_POSITIVE(String amount) {
        // -10.12345 has 5 decimals too, but checks stop at the first failure: one error per amount.
        assertOnlyError(row().with(Field.AMOUNT, amount), "amount", RowErrorCode.AMOUNT_NOT_POSITIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.12345", "1000.00001", "0.00000"})
    void rejects_more_than_four_decimals_as_AMOUNT_SCALE_EXCEEDED(String amount) {
        RowErrorCode expected =
                amount.equals("0.00000") ? RowErrorCode.AMOUNT_NOT_POSITIVE : RowErrorCode.AMOUNT_SCALE_EXCEEDED;
        assertOnlyError(row().with(Field.AMOUNT, amount), "amount", expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000000000000000", "1234567890123456.5", "99999999999999999999"})
    void rejects_more_than_fifteen_integer_digits_as_AMOUNT_OUT_OF_RANGE(String amount) {
        assertOnlyError(row().with(Field.AMOUNT, amount), "amount", RowErrorCode.AMOUNT_OUT_OF_RANGE);
    }

    // --- required fields -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"REFERENCE,reference", "AMOUNT,amount", "CURRENCY,currency", "STATUS,status"})
    void rejects_blank_required_field_as_MISSING_REQUIRED_FIELD(Field field, String column) {
        assertOnlyError(row().with(field, "   "), column, RowErrorCode.MISSING_REQUIRED_FIELD);
    }

    @Test
    void rejects_reference_of_only_non_breaking_spaces_as_MISSING_REQUIRED_FIELD() {
        assertOnlyError(row().with(Field.REFERENCE, "  "), "reference", RowErrorCode.MISSING_REQUIRED_FIELD);
    }

    @Test
    void names_the_gateway_reference_column_merchant_reference() {
        assertOnlyError(gateway().with(Field.REFERENCE, ""), "merchant_reference", RowErrorCode.MISSING_REQUIRED_FIELD);
    }

    @Test
    void rejects_reference_longer_than_128_characters_after_normalization_as_REFERENCE_TOO_LONG() {
        assertThat(validate(row().with(Field.REFERENCE, "  " + "A".repeat(128) + "  "))
                        .isValid())
                .isTrue();
        assertOnlyError(row().with(Field.REFERENCE, "A".repeat(129)), "reference", RowErrorCode.REFERENCE_TOO_LONG);
    }

    // --- currency ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"AED", "aed", " usd ", "EUR", "JPY"})
    void accepts_iso_4217_currency_in_any_case(String currency) {
        assertThat(validate(row().with(Field.CURRENCY, currency)).isValid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "DIRHAM", "AE", "A3D", "€"})
    void rejects_unknown_currency_as_INVALID_CURRENCY(String currency) {
        assertOnlyError(row().with(Field.CURRENCY, currency), "currency", RowErrorCode.INVALID_CURRENCY);
    }

    // --- dates and timestamps ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"01/10/2026", "2026-02-30", "2026-13-01", "20261001", "2026-10-1"})
    void rejects_date_that_is_not_yyyy_mm_dd_as_INVALID_DATE(String date) {
        assertOnlyError(row().with(Field.TRANSACTION_DATE, date), "transaction_date", RowErrorCode.INVALID_DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01T09:15:00", "2026-10-01 09:15:00+04:00", "yesterday", "2026-10-01"})
    void rejects_timestamp_without_an_offset_as_INVALID_TIMESTAMP(String at) {
        assertOnlyError(gateway().with(Field.TRANSACTION_AT, at), "transaction_at", RowErrorCode.INVALID_TIMESTAMP);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-10-01T05:15:00Z",
                "2026-10-01T09:15:00+04:00",
                "2026-10-01T02:00+04:00",
                "2026-10-01T09:15:00.123-05:00"
            })
    void accepts_iso_8601_timestamp_with_offset(String at) {
        assertThat(validate(gateway().with(Field.TRANSACTION_AT, at)).isValid())
                .as(at)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "2024-11-15,DATE_OUT_OF_RANGE",
        "2024-11-16,",
        "2026-12-17,",
        "2026-12-18,DATE_OUT_OF_RANGE",
        "2062-10-01,DATE_OUT_OF_RANGE"
    })
    void rejects_date_outside_the_window_as_DATE_OUT_OF_RANGE(String date, @Nullable RowErrorCode expected) {
        RowValidation result = validate(row().with(Field.TRANSACTION_DATE, date));
        if (expected == null) {
            assertThat(result.isValid()).isTrue();
        } else {
            assertOnlyError(row().with(Field.TRANSACTION_DATE, date), "transaction_date", expected);
        }
    }

    @Test
    void checks_the_window_on_the_utc_date_of_the_timestamp() {
        // 2026-12-18T02:00+04:00 is 2026-12-17T22:00Z: inside the window by its UTC date (N7).
        assertThat(validate(gateway().with(Field.TRANSACTION_AT, "2026-12-18T02:00:00+04:00"))
                        .isValid())
                .isTrue();
        assertOnlyError(
                gateway().with(Field.TRANSACTION_AT, "2026-12-18T05:00:00+04:00"),
                "transaction_at",
                RowErrorCode.DATE_OUT_OF_RANGE);
    }

    @Test
    void requires_a_timestamp_or_a_date_on_a_ledger_row() {
        assertOnlyError(row().with(Field.TRANSACTION_DATE, ""), "transaction_at", RowErrorCode.MISSING_REQUIRED_FIELD);
    }

    @Test
    void requires_a_timestamp_on_a_gateway_row() {
        assertOnlyError(
                gateway().with(Field.TRANSACTION_AT, " "), "transaction_at", RowErrorCode.MISSING_REQUIRED_FIELD);
    }

    @Test
    void uses_the_timestamp_and_ignores_an_invalid_date_when_both_are_given() {
        // N8: transaction_at wins; the file's transaction_date stays in raw_values only.
        assertThat(validate(row().with(Field.TRANSACTION_AT, "2026-10-01T09:15:00+04:00")
                                .with(Field.TRANSACTION_DATE, "nonsense"))
                        .isValid())
                .isTrue();
    }

    // --- mappings ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ON_HOLD", "sale", "ok", "SUCCESSFUL"})
    void rejects_status_outside_the_n9_table_as_UNKNOWN_STATUS(String status) {
        assertOnlyError(row().with(Field.STATUS, status), "status", RowErrorCode.UNKNOWN_STATUS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALE", "transfer", "PAY"})
    void rejects_transaction_type_outside_the_four_as_UNKNOWN_TRANSACTION_TYPE(String type) {
        assertOnlyError(
                row().with(Field.TRANSACTION_TYPE, type), "transaction_type", RowErrorCode.UNKNOWN_TRANSACTION_TYPE);
    }

    // --- whole rows ----------------------------------------------------------------------------

    @Test
    void reports_every_bad_field_of_a_row() {
        RowValidation result = validate(row().with(Field.REFERENCE, "")
                .with(Field.AMOUNT, "1,000.00")
                .with(Field.CURRENCY, "XYZ")
                .with(Field.STATUS, "ON_HOLD")
                .with(Field.TRANSACTION_TYPE, "SALE")
                .with(Field.TRANSACTION_DATE, "01/10/2026"));

        assertThat(result.errors())
                .extracting(FieldError::code)
                .containsExactly(
                        RowErrorCode.MISSING_REQUIRED_FIELD,
                        RowErrorCode.INVALID_AMOUNT,
                        RowErrorCode.INVALID_CURRENCY,
                        RowErrorCode.UNKNOWN_STATUS,
                        RowErrorCode.UNKNOWN_TRANSACTION_TYPE,
                        RowErrorCode.INVALID_DATE);
    }

    @Test
    void reports_a_malformed_row_as_MALFORMED_ROW_and_nothing_else() {
        ParsedRow malformed = new ParsedRow(3, Map.of(), Map.of(), null, "The row has 6 fields; the header has 5");

        assertThat(VALIDATOR.validate(malformed, SourceType.INTERNAL_LEDGER).errors())
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(RowErrorCode.MALFORMED_ROW);
                    assertThat(e.column()).isNull();
                });
    }

    @Test
    void echoes_at_most_64_characters_of_a_bad_value() {
        RowValidation result = validate(row().with(Field.AMOUNT, "x".repeat(10_000)));

        assertThat(result.errors().getFirst().message()).hasSizeLessThan(120);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static RowValidation validate(Row r) {
        return VALIDATOR.validate(r.parsed(), r.type);
    }

    private static void assertOnlyError(Row r, String column, RowErrorCode code) {
        RowValidation result = validate(r);
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.column()).isEqualTo(column);
        });
    }

    /** A valid date-only ledger row, to be spoiled one field at a time. */
    static Row row() {
        return new Row(SourceType.INTERNAL_LEDGER)
                .with(Field.REFERENCE, "PAY-101")
                .with(Field.AMOUNT, "100.00")
                .with(Field.CURRENCY, "AED")
                .with(Field.STATUS, "SUCCESS")
                .with(Field.TRANSACTION_DATE, "2026-10-05");
    }

    /** A valid gateway row. */
    static Row gateway() {
        return new Row(SourceType.GATEWAY)
                .with(Field.REFERENCE, "PAY-101")
                .with(Field.AMOUNT, "1000.00")
                .with(Field.CURRENCY, "aed")
                .with(Field.STATUS, "captured")
                .with(Field.TRANSACTION_AT, "2026-10-01T05:15:00Z");
    }

    static final class Row {
        final SourceType type;
        final Map<Field, String> fields = new EnumMap<>(Field.class);

        Row(SourceType type) {
            this.type = type;
        }

        Row with(Field field, String value) {
            fields.put(field, value);
            return this;
        }

        ParsedRow parsed() {
            return new ParsedRow(1, fields, Map.of(), "raw", null);
        }
    }
}
