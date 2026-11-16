package com.reconflow.paymentreconflow.ingestion.csv;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a file's header to the canonical fields by position. Header names match the contract
 * case-insensitively after trimming ({@code " Reference "} is {@code reference}); column order
 * doesn't matter; unknown columns are allowed (kept in raw_values only). Done here rather than with
 * the parser's ignore-case/trim options so the raw header text stays intact for raw_values.
 */
final class ColumnMapping {

    private ColumnMapping() {}

    static Map<Field, Integer> resolve(SourceType type, List<String> header) {
        Map<String, Integer> positions = new HashMap<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String name = normalize(header.get(i));
            if (name.isEmpty()) {
                continue;
            }
            if (positions.putIfAbsent(name, i) != null) {
                repeated.add(name);
            }
        }
        if (!repeated.isEmpty()) {
            throw new CsvHeaderException(
                    CsvHeaderException.Kind.DUPLICATE_COLUMNS,
                    new ArrayList<>(repeated),
                    "These header names appear more than once: " + String.join(", ", repeated));
        }
        List<String> missing = CsvContract.missingColumns(type, positions.keySet());
        if (!missing.isEmpty()) {
            throw new CsvHeaderException(
                    CsvHeaderException.Kind.MISSING_COLUMNS,
                    missing,
                    "Required columns are missing: " + String.join(", ", missing));
        }
        Map<Field, Integer> index = new EnumMap<>(Field.class);
        CsvContract.columns(type).forEach((field, column) -> {
            Integer position = positions.get(column);
            if (position != null) {
                index.put(field, position);
            }
        });
        return index;
    }

    /** strip(), not trim(): also removes Unicode spaces such as U+00A0 around a header name. */
    static String normalize(String headerName) {
        return headerName.strip().toLowerCase(Locale.ROOT);
    }
}
