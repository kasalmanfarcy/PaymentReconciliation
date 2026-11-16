package com.reconflow.paymentreconflow.reporting.export;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Every exported cell goes through here (M8.4; requirements.md FR-20, "safe to open in a
 * spreadsheet").
 *
 * <p><strong>CSV (formula) injection.</strong> Excel, LibreOffice and Google Sheets run a cell that
 * starts with {@code =}, {@code +}, {@code -} or {@code @} as a formula, and treat a leading tab or
 * carriage return the same way. A gateway reference like {@code =HYPERLINK("http://evil",...)} would
 * become a clickable link, or worse, when an analyst opens the export. {@link #text} prefixes such a
 * cell with a single quote, which the spreadsheet shows as text and doesn't display. The stored data
 * is never changed: this is an output-encoding concern, like HTML escaping.
 *
 * <p>Quoting of commas, quotes and newlines is a different problem, solved by Commons CSV, never by
 * hand.
 *
 * <p>Numbers we format ourselves ({@link #money}, dates, timestamps) skip the check: a plain decimal
 * like {@code -50.0000} is a number to a spreadsheet, not a formula, and quoting it would turn a
 * numeric column into text. Only values that came from users or files go through {@link #text}.
 */
public final class CsvCell {

    private CsvCell() {}

    /** Text from user or file input: references, notes, names, codes. Null becomes an empty cell. */
    public static String text(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return startsLikeAFormula(value) ? "'" + value : value;
    }

    /** Plain notation at its own scale ({@code 1E+3} would be read as a number, but not ours). */
    public static String money(@Nullable BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    public static String date(@Nullable LocalDate value) {
        return value == null ? "" : value.toString();
    }

    public static String timestamp(@Nullable Instant value) {
        return value == null ? "" : value.toString();
    }

    /**
     * The OWASP list ({@code = + - @}, tab, carriage return), plus the same characters after leading
     * spaces (some spreadsheets trim before parsing) and their full-width forms (some convert them).
     */
    static boolean startsLikeAFormula(String value) {
        char first = value.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n') {
            return true;
        }
        int i = 0;
        while (i < value.length() && value.charAt(i) == ' ') {
            i++;
        }
        return i < value.length() && isTrigger(value.charAt(i));
    }

    private static boolean isTrigger(char c) {
        return switch (c) {
            case '=', '+', '-', '@', '＝', '＋', '－', '＠' -> true;
            default -> false;
        };
    }
}
