package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.security.directory.UserDirectory;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /exceptions}: the investigator's work queue, the busiest query in the product
 * (scalability.md §7, S1). Built with JdbcClient and a small clause builder: every user value is a
 * bound parameter, and the sort field is mapped through a whitelist to a column, so no request text
 * ever becomes SQL.
 *
 * <p>Indexes (V1): {@code exception_case_list_idx (organization_id, status, created_at DESC)} serves
 * the default queue; {@code exception_case_assignee_idx} serves {@code assignedTo=<id>|me};
 * {@code exception_case_run}'s primary key {@code (run_id, exception_id)} serves {@code runId}.
 * ExceptionListQueryPlanIT checks the plans.
 */
@Service
@Transactional(readOnly = true)
public class ExceptionList {

    /** API sort field → column. Anything else is 400; user input never reaches SQL as a name. */
    static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "e.created_at",
            "amount", "e.amount",
            "status", "e.status",
            "type", "e.type");

    /** The work queue: resolved cases appear only when asked for. */
    static final List<ExceptionStatus> DEFAULT_STATUSES = List.of(ExceptionStatus.OPEN, ExceptionStatus.IN_REVIEW);

    private static final String COLUMNS = """
            SELECT e.id, e.type, e.status, e.reason_code, e.discrepancy_key, e.currency, e.amount,
                   e.amount_difference, e.assigned_to, e.run_id, e.version, e.created_at, e.updated_at
              FROM exception_case e
            """;

    private final JdbcClient jdbc;
    private final TenantContext tenant;
    private final CurrentUser currentUser;
    private final UserDirectory users;

    ExceptionList(JdbcClient jdbc, TenantContext tenant, CurrentUser currentUser, UserDirectory users) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.users = users;
    }

    public Page<ExceptionSummaryView> list(ExceptionFilter filter, @Nullable List<String> sort, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE e.organization_id = :org");
        Map<String, Object> params = new HashMap<>();
        params.put("org", tenant.organizationId());
        addFilters(filter, where, params);
        String orderBy = orderBy(sort);
        int p = Pagination.page(page);
        int s = Pagination.size(size);

        List<Row> rows = jdbc.sql(COLUMNS + where + orderBy + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", s)
                .param("offset", (long) p * s)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        ExceptionType.valueOf(rs.getString("type")),
                        ExceptionStatus.valueOf(rs.getString("status")),
                        rs.getString("reason_code"),
                        rs.getString("discrepancy_key"),
                        rs.getString("currency"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("amount_difference"),
                        rs.getObject("assigned_to", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("version"),
                        ExceptionQueries.instant(rs, "created_at"),
                        ExceptionQueries.instant(rs, "updated_at")))
                .list();
        // count(*) per request: fine at v1 volumes. If EXPLAIN ever shows it dominating, switch to a
        // Slice as GET /transactions does.
        Long total = jdbc.sql("SELECT count(*) FROM exception_case e" + where)
                .params(params)
                .query(Long.class)
                .single();

        Set<UUID> assignees = new HashSet<>();
        rows.forEach(r -> {
            if (r.assignedTo() != null) {
                assignees.add(r.assignedTo());
            }
        });
        Map<UUID, String> names = users.displayNames(assignees);
        List<ExceptionSummaryView> content = rows.stream()
                .map(r -> new ExceptionSummaryView(
                        r.id(),
                        r.type(),
                        r.status(),
                        r.reasonCode(),
                        Discrepancy.referenceOf(r.discrepancyKey()),
                        r.currency(),
                        r.amount(),
                        r.amountDifference(),
                        UserRef.of(r.assignedTo(), names),
                        r.runId(),
                        r.version(),
                        r.createdAt(),
                        r.updatedAt()))
                .toList();
        return new PageImpl<>(content, PageRequest.of(p, s), total);
    }

    private record Row(
            UUID id,
            ExceptionType type,
            ExceptionStatus status,
            @Nullable String reasonCode,
            String discrepancyKey,
            String currency,
            java.math.BigDecimal amount,
            java.math.@Nullable BigDecimal amountDifference,
            @Nullable UUID assignedTo,
            UUID runId,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    private void addFilters(ExceptionFilter f, StringBuilder where, Map<String, Object> params) {
        if (f.type() != null && !f.type().isEmpty()) {
            where.append(" AND e.type IN (:types)");
            params.put("types", f.type().stream().map(Enum::name).toList());
        }
        List<ExceptionStatus> statuses = f.status() == null || f.status().isEmpty() ? DEFAULT_STATUSES : f.status();
        where.append(" AND e.status IN (:statuses)");
        params.put("statuses", statuses.stream().map(Enum::name).toList());

        if (f.assignedTo() != null) {
            switch (f.assignedTo().strip().toLowerCase(Locale.ROOT)) {
                case "unassigned" -> where.append(" AND e.assigned_to IS NULL");
                case "me" -> {
                    where.append(" AND e.assigned_to = :assignee");
                    params.put("assignee", currentUser.id());
                }
                default -> {
                    // Any user id: one outside the organization simply matches nothing (api-design §4),
                    // so the answer never confirms that a user exists elsewhere.
                    where.append(" AND e.assigned_to = :assignee");
                    params.put("assignee", parseUser(f.assignedTo()));
                }
            }
        }
        if (f.runId() != null) {
            // Every run that SAW the case (exception_case_run), not just the one that raised it
            // (exception_case.run_id): filtering by run means the former.
            where.append(" AND EXISTS (SELECT 1 FROM exception_case_run r WHERE r.exception_id = e.id"
                    + " AND r.organization_id = e.organization_id AND r.run_id = :runId)");
            params.put("runId", f.runId());
        }
        if (f.createdFrom() != null && f.createdTo() != null && f.createdTo().isBefore(f.createdFrom())) {
            throw new ValidationFailedException(
                    "createdTo", "must-not-be-before-start", "createdTo must be on or after createdFrom");
        }
        if (f.createdFrom() != null) {
            where.append(" AND e.created_at >= :createdFrom");
            params.put(
                    "createdFrom",
                    java.sql.Timestamp.from(
                            f.createdFrom().atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (f.createdTo() != null) {
            // Inclusive date: everything before the start of the next UTC day.
            where.append(" AND e.created_at < :createdBefore");
            params.put(
                    "createdBefore",
                    java.sql.Timestamp.from(f.createdTo()
                            .plusDays(1)
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()));
        }
        if (f.amountMin() != null && f.amountMax() != null && f.amountMax().compareTo(f.amountMin()) < 0) {
            throw new ValidationFailedException(
                    "amountMax", "must-not-be-below-min", "amountMax must be at least amountMin");
        }
        if (f.amountMin() != null) {
            where.append(" AND e.amount >= :amountMin");
            params.put("amountMin", f.amountMin());
        }
        if (f.amountMax() != null) {
            where.append(" AND e.amount <= :amountMax");
            params.put("amountMax", f.amountMax());
        }
    }

    private static UUID parseUser(String raw) {
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(
                    "assignedTo", "invalid-assignee-filter", "assignedTo is a user id, 'me' or 'unassigned'");
        }
    }

    /**
     * {@code sort=<field>[,asc|desc]}, repeatable. Spring splits a comma-separated parameter into
     * separate values, so the tokens arrive flat ({@code [amount, desc, createdAt]}); a direction
     * applies to the field before it, and a field without one sorts ascending (Spring Data's
     * convention). Default: newest first. The id always breaks ties, so pages never shift.
     */
    static String orderBy(@Nullable List<String> sort) {
        LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        String last = null;
        for (String raw : sort == null ? List.<String>of() : sort) {
            for (String token : raw.split(",")) {
                String t = token.strip();
                if (t.isEmpty()) {
                    continue;
                }
                String direction = t.toLowerCase(Locale.ROOT);
                if (direction.equals("asc") || direction.equals("desc")) {
                    if (last == null) {
                        throw new ValidationFailedException(
                                "sort", "unsupported-sort-field", "Sortable fields: " + sortable());
                    }
                    keys.put(last, direction.toUpperCase(Locale.ROOT));
                    last = null;
                    continue;
                }
                String column = SORTABLE.get(t);
                if (column == null) {
                    throw new ValidationFailedException(
                            "sort", "unsupported-sort-field", "Sortable fields: " + sortable());
                }
                keys.put(column, "ASC");
                last = column;
            }
        }
        if (keys.isEmpty()) {
            keys.put("e.created_at", "DESC");
        }
        List<String> parts = new ArrayList<>();
        keys.forEach((column, direction) -> parts.add(column + " " + direction));
        parts.add("e.id DESC");
        return " ORDER BY " + String.join(", ", parts);
    }

    private static String sortable() {
        return String.join(", ", new java.util.TreeSet<>(SORTABLE.keySet()));
    }
}
