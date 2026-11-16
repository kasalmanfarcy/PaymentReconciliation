package com.reconflow.paymentreconflow.ingestion.validation;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The validator's answer: either a valid row, or every error found in it (never both). */
public record RowValidation(@Nullable ValidatedRow valid, List<FieldError> errors) {

    public static RowValidation valid(ValidatedRow row) {
        return new RowValidation(row, List.of());
    }

    public static RowValidation invalid(List<FieldError> errors) {
        return new RowValidation(null, List.copyOf(errors));
    }

    public boolean isValid() {
        return valid != null;
    }
}
