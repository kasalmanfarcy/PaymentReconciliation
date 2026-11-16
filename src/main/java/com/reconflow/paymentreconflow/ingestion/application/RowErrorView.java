package com.reconflow.paymentreconflow.ingestion.application;

import org.jspecify.annotations.Nullable;

/** One row error as the API shows it (api-design.md §8.4); {@code column} is null for a whole-row error. */
public record RowErrorView(
        int rowNumber,
        @Nullable String column,
        String code,
        String message,
        @Nullable String rawRow) {}
