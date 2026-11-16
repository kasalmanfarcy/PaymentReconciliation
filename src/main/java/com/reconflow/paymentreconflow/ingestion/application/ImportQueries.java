package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ingestion.domain.ImportRowErrorRepository;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of the caller's imports. Every lookup is by {@code (id, organization_id)} in one indexed
 * query, so another organization's batch is simply not found: {@code 404}, identical to an ID that
 * never existed (ADR 0008), never a {@code 403} that would confirm it exists.
 */
@Service
@Transactional(readOnly = true)
public class ImportQueries {

    private static final String LIST = """
            SELECT b.id, b.source_type, b.status, b.file_name, b.file_size_bytes, b.total_rows, b.valid_rows,
                   b.error_rows, b.failure_reason, b.uploaded_by, b.created_at, b.started_at, b.completed_at,
                   (SELECT count(*) FROM import_row_error e
                     WHERE e.organization_id = b.organization_id AND e.batch_id = b.id) AS stored_errors
              FROM import_batch b
             WHERE b.organization_id = :org %s
             ORDER BY b.created_at %s, b.id %s
             LIMIT :limit OFFSET :offset
            """;

    private final ImportBatchRepository batches;
    private final ImportRowErrorRepository errors;
    private final TenantContext tenant;
    private final JdbcClient jdbc;

    ImportQueries(
            ImportBatchRepository batches, ImportRowErrorRepository errors, TenantContext tenant, JdbcClient jdbc) {
        this.batches = batches;
        this.errors = errors;
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    /**
     * The organization's batches, newest first by default (api-design.md §8.5), served by
     * {@code import_batch_list_idx (organization_id, created_at DESC)}. Filters: any of several
     * statuses, one source type. The only sort field is {@code createdAt}; {@code id} breaks ties so
     * pages never shift or repeat.
     */
    public Page<ImportView> list(
            @Nullable List<ImportBatchStatus> statuses,
            @Nullable SourceType sourceType,
            @Nullable List<String> sort,
            int page,
            int size) {
        UUID organizationId = tenant.organizationId();
        String direction = direction(sort);
        StringBuilder filters = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        params.put("org", organizationId);
        if (statuses != null && !statuses.isEmpty()) {
            filters.append(" AND b.status IN (:statuses)");
            params.put("statuses", statuses.stream().map(Enum::name).toList());
        }
        if (sourceType != null) {
            filters.append(" AND b.source_type = :sourceType");
            params.put("sourceType", sourceType.name());
        }
        int pageNumber = Pagination.page(page);
        int pageSize = Pagination.size(size);
        List<ImportView> content = jdbc.sql(LIST.formatted(filters, direction, direction))
                .params(params)
                .param("limit", pageSize)
                .param("offset", (long) pageNumber * pageSize)
                .query((rs, n) -> new ImportView(
                        rs.getObject("id", UUID.class),
                        SourceType.valueOf(rs.getString("source_type")),
                        ImportBatchStatus.valueOf(rs.getString("status")),
                        rs.getString("file_name"),
                        rs.getLong("file_size_bytes"),
                        rs.getInt("total_rows"),
                        rs.getInt("valid_rows"),
                        rs.getInt("error_rows"),
                        rs.getLong("stored_errors"),
                        rs.getString("failure_reason"),
                        rs.getObject("uploaded_by", UUID.class),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("completed_at"))))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM import_batch b WHERE b.organization_id = :org" + filters)
                .params(params)
                .query(Long.class)
                .single();
        return new PageImpl<>(content, PageRequest.of(pageNumber, pageSize), total);
    }

    /** {@code sort=createdAt[,asc|desc]}; anything else is 400 (user input never reaches SQL). */
    static String direction(@Nullable List<String> sort) {
        List<String> tokens = new ArrayList<>();
        for (String s : sort == null ? List.<String>of() : sort) {
            for (String token : s.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.strip());
                }
            }
        }
        if (tokens.isEmpty()) {
            return "DESC";
        }
        if (!tokens.getFirst().equals("createdAt") || tokens.size() > 2) {
            throw new ValidationFailedException("sort", "unsupported-sort-field", "Sortable fields: createdAt");
        }
        if (tokens.size() == 2) {
            String d = tokens.get(1).toUpperCase(Locale.ROOT);
            if (!d.equals("ASC") && !d.equals("DESC")) {
                throw new ValidationFailedException("sort", "unsupported-sort-direction", "Direction: asc or desc");
            }
            return d;
        }
        return "ASC";
    }

    private static @Nullable Instant instant(@Nullable Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public ImportView get(UUID id) {
        UUID organizationId = tenant.organizationId();
        ImportBatch batch = find(organizationId, id);
        return ImportView.of(batch, errors.countByOrganizationIdAndBatchId(organizationId, id));
    }

    /** Ordered by row number, then column (whole-row errors first): served by the unique key. */
    public Page<RowErrorView> errors(UUID id, int page, int size) {
        UUID organizationId = tenant.organizationId();
        find(organizationId, id);
        PageRequest request = PageRequest.of(
                Pagination.page(page),
                Pagination.size(size),
                Sort.by(
                        Sort.Order.asc("rowNumber"),
                        Sort.Order.asc("columnName").nullsFirst(),
                        Sort.Order.asc("id")));
        return errors.findByOrganizationIdAndBatchId(organizationId, id, request)
                .map(e -> new RowErrorView(
                        e.getRowNumber(), e.getColumnName(), e.getErrorCode(), e.getMessage(), e.getRawRow()));
    }

    private ImportBatch find(UUID organizationId, UUID id) {
        return batches.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new NotFoundException("Import", id));
    }
}
