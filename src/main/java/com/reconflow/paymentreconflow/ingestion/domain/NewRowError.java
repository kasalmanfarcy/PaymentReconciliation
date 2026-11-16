package com.reconflow.paymentreconflow.ingestion.domain;

import com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode;
import org.jspecify.annotations.Nullable;

/** A row error about to be written: what {@link RowErrorWriter} inserts. */
public record NewRowError(
        int rowNumber,
        @Nullable String column,
        RowErrorCode code,
        String message,
        @Nullable String rawRow) {}
