package com.reconflow.paymentreconflow.ingestion.csv;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The column contract of requirements.md §7.2–§7.3, the single place it lives in code: which header
 * name each source uses for each canonical field, and which are required. Change §7 first, then
 * this. Per-organization mappings are v2.
 *
 * <p>The gateway's names differ from the ledger's only in the reference column
 * ({@code merchant_reference}); everything else uses the same names (docs/ win over the phase
 * plan's {@code gross_amount}/{@code settlement_status}/{@code created_at}).
 */
public final class CsvContract {

    /** What upload rejection lists for a ledger with neither date column (requirements.md §7.2). */
    public static final String LEDGER_DATE_EITHER = "transaction_at or transaction_date";

    private static final Map<Field, String> LEDGER = ordered(
            Field.REFERENCE, "reference",
            Field.AMOUNT, "amount",
            Field.CURRENCY, "currency",
            Field.STATUS, "status",
            Field.TRANSACTION_AT, "transaction_at",
            Field.TRANSACTION_DATE, "transaction_date",
            Field.TRANSACTION_TYPE, "transaction_type");

    private static final Map<Field, String> GATEWAY = ordered(
            Field.REFERENCE, "merchant_reference",
            Field.AMOUNT, "amount",
            Field.CURRENCY, "currency",
            Field.STATUS, "status",
            Field.TRANSACTION_AT, "transaction_at",
            Field.TRANSACTION_TYPE, "transaction_type");

    private static final Set<Field> ALWAYS_REQUIRED =
            Set.of(Field.REFERENCE, Field.AMOUNT, Field.CURRENCY, Field.STATUS);

    private CsvContract() {}

    /** Canonical field to this source's header name, in contract order; absent if not in the contract. */
    public static Map<Field, String> columns(SourceType type) {
        return type == SourceType.GATEWAY ? GATEWAY : LEDGER;
    }

    /** The column name of a field for this source, as error messages and row errors show it. */
    public static String columnName(SourceType type, Field field) {
        String name = columns(type).get(field);
        return name != null ? name : field.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Required columns missing from a header (names already trimmed and lower-cased), in contract
     * order. The ledger needs at least one of its two date columns.
     */
    public static List<String> missingColumns(SourceType type, Set<String> header) {
        Map<Field, String> columns = columns(type);
        var missing = new java.util.ArrayList<String>();
        for (var e : columns.entrySet()) {
            boolean required = ALWAYS_REQUIRED.contains(e.getKey())
                    || (type == SourceType.GATEWAY && e.getKey() == Field.TRANSACTION_AT);
            if (required && !header.contains(e.getValue())) {
                missing.add(e.getValue());
            }
        }
        if (type == SourceType.INTERNAL_LEDGER
                && !header.contains(columns.get(Field.TRANSACTION_AT))
                && !header.contains(columns.get(Field.TRANSACTION_DATE))) {
            missing.add(LEDGER_DATE_EITHER);
        }
        return List.copyOf(missing);
    }

    private static Map<Field, String> ordered(Object... pairs) {
        Map<Field, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Field) pairs[i], (String) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
