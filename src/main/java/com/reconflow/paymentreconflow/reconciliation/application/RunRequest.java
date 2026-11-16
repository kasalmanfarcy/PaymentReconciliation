package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.ledger.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code POST /reconciliation-runs}. Bean Validation checks presence; the rules that
 * relate fields (period order and length, sources differ, a real currency) are checked by
 * {@link ReconciliationRunService}, with the same {@code errors[]} shape.
 */
public record RunRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        @NotBlank String currency,
        @Nullable SourceType leftSource,
        @Nullable SourceType rightSource) {}
