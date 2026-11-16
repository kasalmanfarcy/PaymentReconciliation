package com.reconflow.paymentreconflow.reporting.common;

import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Request checks shared by the report endpoints, each failing with {@code 400 validation-failed}
 * and the usual {@code errors[]} entry. Ranges are bounded so one request can't aggregate years
 * of data.
 */
public final class ReportParams {

    /** Longest date range a report accepts, in days (inclusive of both ends). */
    public static final int MAX_RANGE_DAYS = 366;

    private ReportParams() {}

    /** A required parameter. */
    public static <T> T required(@Nullable T value, String name) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new ValidationFailedException(name, "required", name + " is required");
        }
        return value;
    }

    /** {@code to ≥ from} and at most {@link #MAX_RANGE_DAYS} days, both ends inclusive. */
    public static void range(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ValidationFailedException("to", "must-not-be-before-start", "to must be on or after from");
        }
        if (days(from, to) > MAX_RANGE_DAYS) {
            throw new ValidationFailedException(
                    "to", "range-too-long", "the range may cover at most " + MAX_RANGE_DAYS + " days");
        }
    }

    /** Days in {@code [from, to]}. */
    public static long days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Trimmed and upper-cased, then checked against ISO 4217, as the run request does. */
    public static String currency(String raw) {
        String code = raw.strip().toUpperCase(Locale.ROOT);
        try {
            if (code.length() == 3) {
                Currency.getInstance(code);
                return code;
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw new ValidationFailedException("currency", "invalid-currency", "currency must be an ISO-4217 code");
    }
}
