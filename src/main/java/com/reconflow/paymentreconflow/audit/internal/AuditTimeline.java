package com.reconflow.paymentreconflow.audit.internal;

import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One entity's audit timeline (M7.13), oldest first, served by
 * {@code audit_event_entity_idx (organization_id, entity_type, entity_id, created_at)}.
 *
 * <p>An entity of another organization simply has an empty timeline: the organization filter and
 * RLS both apply. A 404 would need a lookup in the entity's own table, which this module doesn't
 * own, and an empty page reveals nothing. Platform events ({@code organization_id IS NULL}) never
 * appear: they aren't the tenant's.
 */
@Service
@Transactional(readOnly = true)
public class AuditTimeline {

    // UUIDv7 ids break created_at ties (one transaction's events share its start time) in insertion order.
    private static final Sort ORDER = Sort.by("createdAt", "id");

    private final AuditEventRepository events;
    private final TenantContext tenant;

    AuditTimeline(AuditEventRepository events, TenantContext tenant) {
        this.events = events;
        this.tenant = tenant;
    }

    public Page<AuditEvent> of(AuditEntityType entityType, UUID entityId, int page, int size) {
        return events.findByOrganizationIdAndEntityTypeAndEntityId(
                tenant.organizationId(),
                entityType,
                entityId,
                PageRequest.of(Pagination.page(page), Pagination.size(size), ORDER));
    }
}
