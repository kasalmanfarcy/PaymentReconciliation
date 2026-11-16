package com.reconflow.paymentreconflow.ledger.internal;

import com.reconflow.paymentreconflow.ledger.ReferenceNormalizer;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.ledger.TransactionStatus;
import com.reconflow.paymentreconflow.ledger.TransactionType;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /transactions} (M5.19): search the caller's normalized records.
 *
 * <ul>
 *   <li>A date range is required, at most {@value #MAX_DAYS} days: with {@code transaction_date}
 *       constrained, PostgreSQL prunes to the months in range instead of scanning every partition
 *       (plans recorded in database-design.md §3.3).</li>
 *   <li>Only records of accepted batches ({@code COMPLETED}, {@code COMPLETED_WITH_ERRORS}; rule
 *       P3): rows of a batch still processing, or one that failed, may yet be deleted by a retry.
 *       That needs a join to ingestion's {@code import_batch}; ledger reads it with SQL, read-only,
 *       the same trade-off the matching engine makes (architecture.md §4), rather than a cross-module
 *       call per page.</li>
 *   <li>Sort fields are whitelisted and mapped to columns here; user input never reaches SQL.</li>
 *   <li>A slice, not a page: no count query.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class TransactionSearch {

    static final int MAX_DAYS = 366;

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "transactionDate", "t.transaction_date",
            "amount", "t.amount",
            "createdAt", "t.created_at");

    public record Criteria(
            LocalDate from,
            LocalDate to,
            @Nullable SourceType sourceType,
            @Nullable String reference,
            @Nullable String currency,
            @Nullable UUID batchId,
            @Nullable List<String> sort,
            int page,
            int size) {}

    private final JdbcClient jdbc;
    private final TenantContext tenant;

    TransactionSearch(JdbcClient jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public SliceView<TransactionView> search(Criteria c) {
        if (c.to().isBefore(c.from())) {
            throw new ValidationFailedException(
                    "transactionDateTo",
                    "must-not-be-before-start",
                    "transactionDateTo must be on or after transactionDateFrom");
        }
        if (ChronoUnit.DAYS.between(c.from(), c.to()) + 1 > MAX_DAYS) {
            throw new ValidationFailedException(
                    "transactionDateTo", "range-too-long", "The date range can cover at most " + MAX_DAYS + " days");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.batch_id, t.source_type, t.source_row_number, t.raw_reference, t.normalized_reference,
                       t.amount, t.currency, t.transaction_type, t.status, t.transaction_date, t.transaction_at,
                       t.is_duplicate_in_source, t.created_at
                  FROM transaction_record t
                  JOIN import_batch b ON b.id = t.batch_id AND b.organization_id = t.organization_id
                 WHERE t.organization_id = :org
                   AND t.transaction_date BETWEEN :from AND :to
                   AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("org", tenant.organizationId());
        params.put("from", c.from());
        params.put("to", c.to());
        if (c.sourceType() != null) {
            sql.append(" AND t.source_type = :sourceType");
            params.put("sourceType", c.sourceType().name());
        }
        if (c.reference() != null && !c.reference().isBlank()) {
            // The same N1-N3 rules as the importer, so "pay 103 " finds what was stored as "PAY 103".
            sql.append(" AND t.normalized_reference = :reference");
            params.put("reference", ReferenceNormalizer.normalize(c.reference()));
        }
        if (c.currency() != null && !c.currency().isBlank()) {
            sql.append(" AND t.currency = :currency");
            params.put("currency", c.currency().strip().toUpperCase(Locale.ROOT));
        }
        if (c.batchId() != null) {
            // Another organization's batch id simply matches nothing; it never confirms it exists.
            sql.append(" AND t.batch_id = :batchId");
            params.put("batchId", c.batchId());
        }
        int page = Pagination.page(c.page());
        int size = Pagination.size(c.size());
        sql.append(" ORDER BY ").append(orderBy(c.sort())).append(" LIMIT :limit OFFSET :offset");
        params.put("limit", size + 1); // one extra row answers hasNext without a count
        params.put("offset", (long) page * size);

        List<TransactionView> rows = jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new TransactionView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        SourceType.valueOf(rs.getString("source_type")),
                        rs.getInt("source_row_number"),
                        rs.getString("raw_reference"),
                        rs.getString("normalized_reference"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        TransactionType.valueOf(rs.getString("transaction_type")),
                        TransactionStatus.valueOf(rs.getString("status")),
                        rs.getObject("transaction_date", LocalDate.class),
                        instant(rs.getTimestamp("transaction_at")),
                        rs.getBoolean("is_duplicate_in_source"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        boolean hasNext = rows.size() > size;
        List<TransactionView> content = hasNext ? rows.subList(0, size) : rows;
        return new SliceView<>(List.copyOf(content), new SliceView.PageInfo(page, size, hasNext));
    }

    /** {@code sort=amount,desc&sort=transactionDate}; default transaction date ascending; id breaks ties. */
    static String orderBy(@Nullable List<String> sort) {
        List<String> tokens = new ArrayList<>();
        for (String s : sort == null ? List.<String>of() : sort) {
            for (String token : s.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.strip());
                }
            }
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String column = SORT_COLUMNS.get(tokens.get(i));
            if (column == null) {
                throw new ValidationFailedException(
                        "sort",
                        "unsupported-sort-field",
                        "Sortable fields: " + String.join(", ", SORT_COLUMNS.keySet()));
            }
            String direction = "ASC";
            if (i + 1 < tokens.size()
                    && (tokens.get(i + 1).equalsIgnoreCase("asc")
                            || tokens.get(i + 1).equalsIgnoreCase("desc"))) {
                direction = tokens.get(++i).toUpperCase(Locale.ROOT);
            }
            parts.add(column + " " + direction);
        }
        if (parts.isEmpty()) {
            parts.add("t.transaction_date ASC");
        }
        parts.add("t.id ASC"); // a unique tiebreaker: pages never shift or repeat
        return String.join(", ", parts);
    }

    private static java.time.@Nullable Instant instant(@Nullable Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
