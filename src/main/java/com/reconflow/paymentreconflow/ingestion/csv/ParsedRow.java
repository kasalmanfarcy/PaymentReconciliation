package com.reconflow.paymentreconflow.ingestion.csv;

import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One data record of a file, before validation.
 *
 * @param number 1-based data record number: the header, empty lines and line breaks inside quoted
 *     fields don't count (what {@code import_row_error.row_number} and
 *     {@code source_row_number} store)
 * @param fields canonical field to raw value, only for fields whose column exists in the header;
 *     values exactly as in the file (validation trims what it needs to)
 * @param rawValues every column, original header text to original value (rule N11)
 * @param rawRow the row as a user would recognise it: the values re-serialized as CSV, not the
 *     byte-exact line (the parser doesn't keep that); for a syntax error, the physical line
 * @param malformed null for a well-formed row; otherwise why it can't be read (wrong field count,
 *     broken quoting), and no other check runs on it
 */
public record ParsedRow(
        int number,
        Map<Field, String> fields,
        Map<String, String> rawValues,
        @Nullable String rawRow,
        @Nullable String malformed) {

    public ParsedRow {
        fields = fields.isEmpty() ? Map.of() : new EnumMap<>(fields);
    }

    public @Nullable String get(Field field) {
        return fields.get(field);
    }

    public boolean has(Field field) {
        return fields.containsKey(field);
    }

    public boolean isMalformed() {
        return malformed != null;
    }
}
