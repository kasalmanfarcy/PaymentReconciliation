package com.reconflow.paymentreconflow.ingestion.csv;

/**
 * The bytes aren't valid UTF-8. The decoder is strict, so this is reported instead of silently
 * importing {@code U+FFFD} replacement characters: a corrupted reference would never match its
 * counterpart and nobody would know why. Field positions can't be trusted past this point, so the
 * whole file fails ({@code invalid-encoding at row N}).
 */
public class CsvEncodingException extends RuntimeException {

    private final int rowNumber;

    public CsvEncodingException(int rowNumber, Throwable cause) {
        super("invalid-encoding at row " + rowNumber, cause);
        this.rowNumber = rowNumber;
    }

    /** The data row being read; 0 means the header. */
    public int rowNumber() {
        return rowNumber;
    }
}
