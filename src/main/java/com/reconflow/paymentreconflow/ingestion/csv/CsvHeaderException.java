package com.reconflow.paymentreconflow.ingestion.csv;

import java.util.List;

/** The header row can't be used: what upload answers with a 400, before anything is stored. */
public class CsvHeaderException extends RuntimeException {

    public enum Kind {
        /** No header line at all (zero bytes, or only empty lines). */
        NO_HEADER,
        MISSING_COLUMNS,
        DUPLICATE_COLUMNS
    }

    private final Kind kind;
    private final List<String> columns;

    public CsvHeaderException(Kind kind, List<String> columns, String message) {
        super(message);
        this.kind = kind;
        this.columns = List.copyOf(columns);
    }

    public Kind kind() {
        return kind;
    }

    /** The missing (or repeated) column names. */
    public List<String> columns() {
        return columns;
    }
}
