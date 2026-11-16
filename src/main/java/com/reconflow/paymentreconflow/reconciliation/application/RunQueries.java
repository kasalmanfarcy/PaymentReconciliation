package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRunRepository;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import java.util.UUID;
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

    private final ReconciliationRunRepository runs;
    private final TenantContext tenant;

    RunQueries(ReconciliationRunRepository runs, TenantContext tenant) {
        this.runs = runs;
        this.tenant = tenant;
    }

    /** Status, progress and, once COMPLETED, the summary. Cheap to poll: one primary-key lookup. */
    public RunView get(UUID id) {
        return runs.findByIdAndOrganizationId(id, tenant.organizationId())
                .map(RunView::of)
                .orElseThrow(() -> new NotFoundException("Reconciliation run", id));
    }
}
