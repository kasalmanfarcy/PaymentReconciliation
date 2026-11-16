package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.RunExceptionSummary;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.domain.RunSummary;
import java.math.BigDecimal;

/**
 * Maps the exceptions a run observed onto the run's summary columns (phase-M6 §M6.9). The case
 * types are named by <em>source</em> (MISSING_IN_GATEWAY: only the ledger has it), the columns by
 * <em>side</em> (unmatched_left: records of the left source nobody matched), so which type feeds
 * which column depends on the run's left source.
 */
final class RunSummaries {

    private RunSummaries() {}

    static RunSummary of(
            SourceType leftSource,
            int matchedCount,
            BigDecimal matchedAmount,
            RunExceptionSummary exceptions,
            int excludedCount) {
        RunExceptionSummary.Totals left = exceptions.of(missingCounterpartOf(leftSource));
        RunExceptionSummary.Totals right = exceptions.of(missingCounterpartOf(other(leftSource)));
        RunExceptionSummary.Totals mismatches = exceptions.of(ExceptionType.AMOUNT_MISMATCH);
        return new RunSummary(
                matchedCount,
                matchedAmount,
                mismatches.cases(),
                mismatches.netDifference(),
                left.cases(),
                left.amount(),
                right.cases(),
                right.amount(),
                exceptions.of(ExceptionType.DUPLICATE_IN_SOURCE).cases(),
                exceptions.of(ExceptionType.CURRENCY_MISMATCH).cases(),
                excludedCount);
    }

    /** The case type for a record of {@code source} with no counterpart in the other source. */
    static ExceptionType missingCounterpartOf(SourceType source) {
        return switch (source) {
            case INTERNAL_LEDGER -> ExceptionType.MISSING_IN_GATEWAY;
            case GATEWAY -> ExceptionType.MISSING_IN_LEDGER;
        };
    }

    private static SourceType other(SourceType source) {
        return source == SourceType.INTERNAL_LEDGER ? SourceType.GATEWAY : SourceType.INTERNAL_LEDGER;
    }
}
