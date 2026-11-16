package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.NewRowError;
import com.reconflow.paymentreconflow.ingestion.validation.FieldError;
import com.reconflow.paymentreconflow.ledger.NewTransactionRecord;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The rows buffered for one transaction: at most {@code chunk-size} rows, so memory is bounded by
 * the chunk, never by the file. Cleared after each commit.
 *
 * <p>Row errors are stored only while the batch's error budget lasts (10,000 by default: a
 * 500k-row file with every row wrong would otherwise write millions of error rows), but every bad
 * row is still counted in {@code error_rows}.
 */
final class Chunk {

    private final List<NewTransactionRecord> valid = new ArrayList<>();
    private final List<NewRowError> errors = new ArrayList<>();
    private final Set<YearMonth> months = new HashSet<>();
    private int errorRows;
    private int errorBudget;

    Chunk(int errorBudget) {
        this.errorBudget = Math.max(errorBudget, 0);
    }

    void addValid(NewTransactionRecord record) {
        valid.add(record);
        months.add(YearMonth.from(record.transactionDate()));
    }

    void addErrors(int rowNumber, List<FieldError> rowErrors, @Nullable String rawRow) {
        errorRows++;
        for (FieldError e : rowErrors) {
            if (errorBudget == 0) {
                return;
            }
            errors.add(new NewRowError(rowNumber, e.column(), e.code(), e.message(), rawRow));
            errorBudget--;
        }
    }

    int rows() {
        return valid.size() + errorRows;
    }

    boolean isEmpty() {
        return rows() == 0;
    }

    List<NewTransactionRecord> valid() {
        return valid;
    }

    List<NewRowError> errors() {
        return errors;
    }

    int errorRows() {
        return errorRows;
    }

    Set<YearMonth> months() {
        return months;
    }

    void clear() {
        valid.clear();
        errors.clear();
        months.clear();
        errorRows = 0;
    }
}
