package com.reconflow.paymentreconflow.reconciliation.engine;

import com.reconflow.paymentreconflow.exceptions.RecordRef;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One classified group from {@code recon_group}, as the strategy reads it back. */
record GroupRow(
        String normalizedReference,
        String transactionType,
        List<String> currencies,
        int ledgerN,
        int gatewayN,
        List<RecordRef> ledgerRecords,
        List<RecordRef> gatewayRecords,
        @Nullable BigDecimal ledgerAmount,
        @Nullable BigDecimal gatewayAmount,
        boolean sameAmount,
        String outcome) {

    static GroupRow map(ResultSet rs, int rowNum) throws SQLException {
        return new GroupRow(
                rs.getString("normalized_reference"),
                rs.getString("transaction_type"),
                Arrays.asList((String[]) rs.getArray("currencies").getArray()),
                rs.getInt("ledger_n"),
                rs.getInt("gateway_n"),
                refs(rs.getArray("ledger_ids"), rs.getArray("ledger_dates")),
                refs(rs.getArray("gateway_ids"), rs.getArray("gateway_dates")),
                rs.getBigDecimal("ledger_amount"),
                rs.getBigDecimal("gateway_amount"),
                rs.getBoolean("same_amount"),
                rs.getString("outcome"));
    }

    /** Ledger records first, then gateway: every record of the group. */
    List<RecordRef> allRecords() {
        List<RecordRef> all = new ArrayList<>(ledgerRecords);
        all.addAll(gatewayRecords);
        return all;
    }

    private static List<RecordRef> refs(@Nullable Array ids, @Nullable Array dates) throws SQLException {
        if (ids == null) {
            return List.of(); // array_agg ... FILTER over no rows is NULL, not an empty array
        }
        UUID[] idValues = (UUID[]) ids.getArray();
        Date[] dateValues = (Date[]) dates.getArray();
        List<RecordRef> refs = new ArrayList<>(idValues.length);
        for (int i = 0; i < idValues.length; i++) {
            refs.add(new RecordRef(idValues[i], dateValues[i].toLocalDate()));
        }
        return refs;
    }
}
