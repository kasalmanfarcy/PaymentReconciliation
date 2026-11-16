package com.reconflow.paymentreconflow.reconciliation.engine;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import java.math.BigDecimal;
import java.util.List;

/**
 * One day-chunk's output: how many matches it inserted and their total (the ledger amount, which
 * equals the gateway amount for an exact match), and its discrepancies, sorted by key so
 * every transaction that records them takes row locks in the same order (no deadlocks between
 * concurrent runs, phase-M6 §M6.6).
 */
public record DayResult(int matchesInserted, BigDecimal matchedAmount, List<Discrepancy> discrepancies) {

    public DayResult {
        discrepancies = List.copyOf(discrepancies);
    }
}
