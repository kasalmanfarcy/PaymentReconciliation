package com.reconflow.paymentreconflow.ingestion.validation;

import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.AMOUNT_NOT_POSITIVE;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.AMOUNT_OUT_OF_RANGE;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.AMOUNT_SCALE_EXCEEDED;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.DATE_OUT_OF_RANGE;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.INVALID_AMOUNT;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.INVALID_CURRENCY;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.INVALID_DATE;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.INVALID_TIMESTAMP;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.MALFORMED_ROW;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.MISSING_REQUIRED_FIELD;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.REFERENCE_TOO_LONG;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.UNKNOWN_STATUS;
import static com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode.UNKNOWN_TRANSACTION_TYPE;

import com.reconflow.paymentreconflow.ingestion.application.ImportProperties;
import com.reconflow.paymentreconflow.ingestion.csv.CsvContract;
import com.reconflow.paymentreconflow.ingestion.csv.Field;
import com.reconflow.paymentreconflow.ingestion.csv.ParsedRow;
import com.reconflow.paymentreconflow.ingestion.normalization.StatusMapping;
import com.reconflow.paymentreconflow.ingestion.normalization.TransactionTypeMapping;
import com.reconflow.paymentreconflow.ledger.ReferenceNormalizer;
import com.reconflow.paymentreconflow.ledger.SourceType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Every check of requirements.md §7.1 and §7.5 on one row (M5.11), collecting <em>all</em> errors
 * so a user fixes a file in one round, not five. Errors are data: this never throws for bad input.
 *
 * <p>Order per field follows §7.5; amount checks stop at the first failure, so one bad amount gets
 * one error ({@code -5.00} is AMOUNT_NOT_POSITIVE only). A malformed row gets MALFORMED_ROW and
 * nothing else, because its field positions can't be trusted.
 */
@Component
public class RowValidator {

    /**
     * A plain decimal: optional sign, digits, optional point and digits. Rejects thousands
     * separators, currency symbols, exponents ({@code 1E+3}, which {@code new BigDecimal} would
     * otherwise accept) and a bare {@code .5}. The minus sign is accepted here only so that
     * {@code -5.00} is reported as not positive rather than as unreadable.
     */
    static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    static final int MAX_SCALE = 4;
    static final int MAX_INTEGER_DIGITS = 15; // NUMERIC(19,4)

    private static final Pattern CURRENCY_SHAPE = Pattern.compile("[A-Z]{3}");
    private static final Set<String> ISO_CURRENCIES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode)
            .collect(Collectors.toUnmodifiableSet());
    private static final int MAX_SHOWN = 64;

    private final ImportProperties props;
    private final Clock clock;

    public RowValidator(ImportProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public RowValidation validate(ParsedRow row, SourceType type) {
        if (row.isMalformed()) {
            return RowValidation.invalid(List.of(FieldError.wholeRow(MALFORMED_ROW, row.malformed())));
        }
        List<FieldError> errors = new ArrayList<>();
        String reference = reference(row, type, errors);
        BigDecimal amount = amount(row, type, errors);
        String currency = currency(row, type, errors);
        String status = status(row, type, errors);
        String transactionType = transactionType(row, type, errors);
        Dated dated = dates(row, type, errors);
        if (!errors.isEmpty()) {
            return RowValidation.invalid(errors);
        }
        return RowValidation.valid(new ValidatedRow(
                row.number(),
                reference,
                amount,
                currency,
                status,
                transactionType,
                dated.at(),
                dated.date(),
                row.rawValues()));
    }

    private @Nullable String reference(ParsedRow row, SourceType type, List<FieldError> errors) {
        String column = CsvContract.columnName(type, Field.REFERENCE);
        String raw = row.get(Field.REFERENCE);
        // Also blank once normalized: a reference of only non-breaking spaces is no reference.
        if (isBlank(raw) || ReferenceNormalizer.normalize(raw).isEmpty()) {
            errors.add(missing(column));
            return null;
        }
        if (ReferenceNormalizer.normalize(raw).length() > ReferenceNormalizer.MAX_LENGTH) {
            errors.add(new FieldError(
                    column,
                    REFERENCE_TOO_LONG,
                    "Reference is longer than " + ReferenceNormalizer.MAX_LENGTH + " characters"));
            return null;
        }
        return raw; // the raw reference, exactly as in the file (N11); the normalizer derives the rest
    }

    /** Parsed from the string, never via a double: 0.1 + 0.2 != 0.3 in binary floating point. */
    private @Nullable BigDecimal amount(ParsedRow row, SourceType type, List<FieldError> errors) {
        String column = CsvContract.columnName(type, Field.AMOUNT);
        String raw = row.get(Field.AMOUNT);
        if (isBlank(raw)) {
            errors.add(missing(column));
            return null;
        }
        String s = raw.strip();
        if (!PLAIN_DECIMAL.matcher(s).matches()) {
            errors.add(new FieldError(
                    column, INVALID_AMOUNT, "Amount '" + shown(raw) + "' is not a plain decimal number"));
            return null;
        }
        BigDecimal amount = new BigDecimal(s);
        if (amount.signum() <= 0) {
            errors.add(new FieldError(
                    column, AMOUNT_NOT_POSITIVE, "Amount '" + shown(raw) + "' must be greater than zero"));
            return null;
        }
        if (amount.scale() > MAX_SCALE) {
            // Rejected, never rounded: we don't get to change someone's money.
            errors.add(new FieldError(
                    column, AMOUNT_SCALE_EXCEEDED, "Amount '" + shown(raw) + "' has more than 4 decimal places"));
            return null;
        }
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            errors.add(new FieldError(
                    column, AMOUNT_OUT_OF_RANGE, "Amount '" + shown(raw) + "' has more than 15 integer digits"));
            return null;
        }
        return amount;
    }

    private @Nullable String currency(ParsedRow row, SourceType type, List<FieldError> errors) {
        String column = CsvContract.columnName(type, Field.CURRENCY);
        String raw = row.get(Field.CURRENCY);
        if (isBlank(raw)) {
            errors.add(missing(column));
            return null;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        // A known-codes set rather than Currency.getInstance, which throws for unknown codes.
        if (!CURRENCY_SHAPE.matcher(code).matches() || !ISO_CURRENCIES.contains(code)) {
            errors.add(
                    new FieldError(column, INVALID_CURRENCY, "Currency '" + shown(raw) + "' is not an ISO 4217 code"));
            return null;
        }
        return raw;
    }

    private @Nullable String status(ParsedRow row, SourceType type, List<FieldError> errors) {
        String column = CsvContract.columnName(type, Field.STATUS);
        String raw = row.get(Field.STATUS);
        if (isBlank(raw)) {
            errors.add(missing(column));
            return null;
        }
        if (StatusMapping.map(raw).isEmpty()) {
            errors.add(new FieldError(column, UNKNOWN_STATUS, "Status '" + shown(raw) + "' is not a known status"));
            return null;
        }
        return raw;
    }

    private @Nullable String transactionType(ParsedRow row, SourceType type, List<FieldError> errors) {
        String raw = row.get(Field.TRANSACTION_TYPE);
        if (TransactionTypeMapping.map(raw).isEmpty()) {
            errors.add(new FieldError(
                    CsvContract.columnName(type, Field.TRANSACTION_TYPE),
                    UNKNOWN_TRANSACTION_TYPE,
                    "Transaction type '" + shown(raw) + "' is not PAYMENT, REFUND, CHARGEBACK or FEE"));
        }
        return raw;
    }

    private record Dated(
            @Nullable OffsetDateTime at, @Nullable LocalDate date) {}

    /**
     * {@code transaction_at} (ISO 8601 with an offset) wins when present (N8); a date-only ledger row
     * uses {@code transaction_date}. Either way the record's date (N7) must be inside the window.
     */
    private Dated dates(ParsedRow row, SourceType type, List<FieldError> errors) {
        String atColumn = CsvContract.columnName(type, Field.TRANSACTION_AT);
        String rawAt = row.get(Field.TRANSACTION_AT);
        if (!isBlank(rawAt)) {
            OffsetDateTime at;
            try {
                // An offset is required: a bare local time is ambiguous, and guessing a zone can
                // move a payment across midnight.
                at = OffsetDateTime.parse(rawAt.strip(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException e) {
                errors.add(new FieldError(
                        atColumn,
                        INVALID_TIMESTAMP,
                        "Timestamp '" + shown(rawAt)
                                + "' is not ISO 8601 with an offset, e.g. 2026-10-01T09:15:00+04:00"));
                return new Dated(null, null);
            }
            checkWindow(at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate(), atColumn, errors);
            return new Dated(at, null);
        }
        String dateColumn = CsvContract.columnName(type, Field.TRANSACTION_DATE);
        String rawDate = type == SourceType.INTERNAL_LEDGER ? row.get(Field.TRANSACTION_DATE) : null;
        if (isBlank(rawDate)) {
            errors.add(missing(atColumn));
            return new Dated(null, null);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(rawDate.strip(), DateTimeFormatter.ISO_LOCAL_DATE); // strict: no 2026-02-30
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(dateColumn, INVALID_DATE, "Date '" + shown(rawDate) + "' is not YYYY-MM-DD"));
            return new Dated(null, null);
        }
        checkWindow(date, dateColumn, errors);
        return new Dated(null, date);
    }

    private void checkWindow(LocalDate date, String column, List<FieldError> errors) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate oldest = today.minus(props.oldestTransactionDate());
        LocalDate newest = today.plus(props.newestTransactionDate());
        if (date.isBefore(oldest) || date.isAfter(newest)) {
            errors.add(new FieldError(
                    column,
                    DATE_OUT_OF_RANGE,
                    "Date " + date + " is outside the accepted range " + oldest + " to " + newest));
        }
    }

    private static FieldError missing(String column) {
        return new FieldError(column, MISSING_REQUIRED_FIELD, "Required field '" + column + "' is blank");
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    /** User input echoed in a message, shortened so a 10 MB "amount" doesn't become a 10 MB error. */
    private static String shown(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_SHOWN ? raw : raw.substring(0, MAX_SHOWN) + "…";
    }
}
