package com.reconflow.paymentreconflow.exceptions;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Per type, the cases one run observed: how many, the sum of their {@code amount} and the sum of
 * their {@code amount_difference}. Counts are cases, not records.
 */
public record RunExceptionSummary(Map<ExceptionType, Totals> byType) {

    public record Totals(int cases, BigDecimal amount, BigDecimal netDifference) {

        public static final Totals NONE = new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public RunExceptionSummary {
        byType = Map.copyOf(byType);
    }

    /** Never null: a type the run didn't see has zero cases and zero amounts. */
    public Totals of(ExceptionType type) {
        return byType.getOrDefault(type, Totals.NONE);
    }
}
