package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.reconciliation.domain.MatchType;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRunRepository;
import com.reconflow.paymentreconflow.reconciliation.domain.RunStatus;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of the caller's runs. Every lookup is by {@code (id, organization_id)} in one indexed
 * query, so another organization's run is simply not found: {@code 404}, identical to an ID that
 * never existed (ADR 0008), never a {@code 403} that would confirm it exists.
 */
@Service
@Transactional(readOnly = true)
public class RunQueries {

    // Joined on (id, transaction_date), the partitioned table's full key: each lookup prunes to one
    // partition. Sorted by ledger reference; the match id breaks ties so pages never shift.
    private static final String MATCHES = """
            SELECT m.id, m.match_type, m.amount_difference, l.currency,
                   l.id AS l_id, l.raw_reference AS l_ref, l.normalized_reference AS l_norm, l.amount AS l_amount,
                   l.transaction_date AS l_date,
                   g.id AS g_id, g.raw_reference AS g_ref, g.normalized_reference AS g_norm, g.amount AS g_amount,
                   g.transaction_date AS g_date
              FROM reconciliation_match m
              JOIN transaction_record l
                ON l.id = m.ledger_record_id AND l.transaction_date = m.ledger_txn_date
               AND l.organization_id = m.organization_id
              JOIN transaction_record g
                ON g.id = m.gateway_record_id AND g.transaction_date = m.gateway_txn_date
               AND g.organization_id = m.organization_id
             WHERE m.organization_id = :org AND m.run_id = :runId
             ORDER BY l.normalized_reference, m.id
             LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT_MATCHES =
            "SELECT count(*) FROM reconciliation_match WHERE organization_id = :org AND run_id = :runId";

    private final ReconciliationRunRepository runs;
    private final TenantContext tenant;
    private final JdbcClient jdbc;

    RunQueries(ReconciliationRunRepository runs, TenantContext tenant, JdbcClient jdbc) {
        this.runs = runs;
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    /** Status, progress and, once COMPLETED, the summary. Cheap to poll: one primary-key lookup. */
    public RunView get(UUID id) {
        return runs.findByIdAndOrganizationId(id, tenant.organizationId())
                .map(RunView::of)
                .orElseThrow(() -> new NotFoundException("Reconciliation run", id));
    }

    /**
     * A run's matches, both records side by side, paginated (default 50, clamped to 200). The run
     * is looked up first, so another tenant's run is a 404, not an empty page.
     */
    public Page<MatchView> matches(UUID runId, int page, int size) {
        UUID organizationId = tenant.organizationId();
        if (runs.findByIdAndOrganizationId(runId, organizationId).isEmpty()) {
            throw new NotFoundException("Reconciliation run", runId);
        }
        int p = Pagination.page(page);
        int s = Pagination.size(size);
        List<MatchView> content = jdbc.sql(MATCHES)
                .param("org", organizationId)
                .param("runId", runId)
                .param("limit", s)
                .param("offset", (long) p * s)
                .query((rs, n) -> new MatchView(
                        rs.getObject("id", UUID.class),
                        MatchType.valueOf(rs.getString("match_type")),
                        rs.getString("currency"),
                        rs.getBigDecimal("amount_difference"),
                        side(rs, "l_"),
                        side(rs, "g_")))
                .list();
        Long total = jdbc.sql(COUNT_MATCHES)
                .param("org", organizationId)
                .param("runId", runId)
                .query(Long.class)
                .single();
        return new PageImpl<>(content, PageRequest.of(p, s), total);
    }

    private static MatchView.Side side(ResultSet rs, String prefix) throws SQLException {
        return new MatchView.Side(
                rs.getObject(prefix + "id", UUID.class),
                rs.getString(prefix + "ref"),
                rs.getString(prefix + "norm"),
                rs.getBigDecimal(prefix + "amount"),
                rs.getObject(prefix + "date", LocalDate.class));
    }

    /**
     * The organization's runs, newest first by default (api-design.md §8.12), served by
     * {@code reconciliation_run_list_idx (organization_id, created_at DESC)}. Filters: any of
     * several statuses; a date range the run's period overlaps. The only sort field is
     * {@code createdAt}; the id breaks ties. One query picks the page's ids (filters, order and
     * paging in SQL), a second loads those runs.
     */
    public Page<RunView> list(
            @Nullable List<RunStatus> statuses,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable List<String> sort,
            int page,
            int size) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ValidationFailedException("to", "must-not-be-before-start", "to must be on or after from");
        }
        UUID organizationId = tenant.organizationId();
        String direction = direction(sort);
        StringBuilder where = new StringBuilder(" WHERE organization_id = :org");
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        if (statuses != null && !statuses.isEmpty()) {
            where.append(" AND status IN (:statuses)");
            params.put("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (to != null) {
            where.append(" AND period_start <= :to"); // the period overlaps [from, to]
            params.put("to", to);
        }
        if (from != null) {
            where.append(" AND period_end >= :from");
            params.put("from", from);
        }
        int p = Pagination.page(page);
        int s = Pagination.size(size);
        List<UUID> ids = jdbc.sql("SELECT id FROM reconciliation_run" + where + " ORDER BY created_at " + direction
                        + ", id " + direction + " LIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", s)
                .param("offset", (long) p * s)
                .query(UUID.class)
                .list();
        Long total = jdbc.sql("SELECT count(*) FROM reconciliation_run" + where)
                .params(params)
                .query(Long.class)
                .single();
        Map<UUID, ReconciliationRun> loaded = new HashMap<>();
        if (!ids.isEmpty()) {
            runs.findByOrganizationIdAndIdIn(organizationId, ids).forEach(r -> loaded.put(r.getId(), r));
        }
        List<RunView> content = ids.stream().map(loaded::get).map(RunView::of).toList();
        return new PageImpl<>(content, PageRequest.of(p, s), total);
    }

    /** {@code sort=createdAt,asc|desc}; anything else is 400 (fields are whitelisted). */
    private static String direction(@Nullable List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return "DESC";
        }
        List<String> tokens = sort.stream()
                .flatMap(x -> Arrays.stream(x.split(",")))
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .toList();
        if (tokens.isEmpty() || !tokens.getFirst().equals("createdAt") || tokens.size() > 2) {
            throw new ValidationFailedException("sort", "unsupported-sort-field", "Sortable fields: createdAt");
        }
        if (tokens.size() == 1) {
            return "DESC";
        }
        return switch (tokens.get(1).toLowerCase(Locale.ROOT)) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default ->
                throw new ValidationFailedException("sort", "unsupported-sort-direction", "Direction: asc or desc");
        };
    }
}
