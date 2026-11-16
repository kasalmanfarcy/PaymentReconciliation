package com.reconflow.paymentreconflow.ingestion.validation;

/**
 * Why a row was rejected: the codes of requirements.md §7.5, stored in
 * {@code import_row_error.error_code}. There is no CHECK on that column (database-design.md §3.3),
 * so this enum is the list; a new code is a code change plus a row in §7.5.
 *
 * <p>Clients may branch on these, so never rename one. The human-readable message is built per
 * error by the validator and never contains a stack trace or class name: it is shown to users.
 */
public enum RowErrorCode {
    /** Broken RFC 4180 quoting or a field count that differs from the header. Whole row. */
    MALFORMED_ROW,
    MISSING_REQUIRED_FIELD,
    /** Not a plain decimal: {@code 1,000.00}, {@code AED 10}, {@code 1E+3}, {@code .5}. */
    INVALID_AMOUNT,
    /** A plain decimal that is zero or negative; direction comes from transaction_type (A8). */
    AMOUNT_NOT_POSITIVE,
    /** More than 4 decimal places: rejected, never rounded (N6). */
    AMOUNT_SCALE_EXCEEDED,
    /** More than 15 integer digits: doesn't fit NUMERIC(19,4). */
    AMOUNT_OUT_OF_RANGE,
    INVALID_CURRENCY,
    INVALID_DATE,
    INVALID_TIMESTAMP,
    DATE_OUT_OF_RANGE,
    UNKNOWN_STATUS,
    UNKNOWN_TRANSACTION_TYPE,
    REFERENCE_TOO_LONG
}
