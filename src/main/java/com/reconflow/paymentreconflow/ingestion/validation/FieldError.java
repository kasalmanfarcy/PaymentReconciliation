package com.reconflow.paymentreconflow.ingestion.validation;

import org.jspecify.annotations.Nullable;

/**
 * One problem with one row: the column it concerns ({@code null} for whole-row errors such as
 * {@link RowErrorCode#MALFORMED_ROW}), the code, and a message a user can act on. Errors are data,
 * not exceptions: the validator returns them and processing carries on with the next row.
 */
public record FieldError(@Nullable String column, RowErrorCode code, String message) {

    public static FieldError wholeRow(RowErrorCode code, String message) {
        return new FieldError(null, code, message);
    }
}
