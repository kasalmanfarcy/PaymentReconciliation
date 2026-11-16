package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportRowErrorRepository;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private final ImportBatchRepository batches;
    private final ImportRowErrorRepository errors;
    private final TenantContext tenant;

    ImportQueries(ImportBatchRepository batches, ImportRowErrorRepository errors, TenantContext tenant) {
        this.batches = batches;
        this.errors = errors;
        this.tenant = tenant;
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
