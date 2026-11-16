package com.reconflow.paymentreconflow.reconciliation.domain;

import java.math.BigDecimal;

/**
 * A completed run's totals, computed from scratch in the completing transaction so a resumed run
 * gets exactly the numbers of a clean one (M6.9). Counts are <em>cases</em>, not records: a
 * duplicate case linking three records counts once (api-design.md §8.8). {@code excludedCount} is
 * records, the ones that failed rule P4 or P5 (reconciliation-rules.md §2).
 */
public record RunSummary(
        int matchedCount,
        BigDecimal matchedAmount,
        int mismatchCount,
        BigDecimal mismatchNetDifference,
        int unmatchedLeftCount,
        BigDecimal unmatchedLeftAmount,
        int unmatchedRightCount,
        BigDecimal unmatchedRightAmount,
        int duplicateCount,
        int currencyMismatchCount,
        int excludedCount) {}
