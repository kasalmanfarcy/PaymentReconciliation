package com.reconflow.paymentreconflow.audit.internal.api;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.internal.AuditEvent;
import com.reconflow.paymentreconflow.audit.internal.AuditTimeline;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/audit-events?entityType=&entityId=} (api-design.md §8.19): the raw,
 * authoritative trail of one entity for admins and auditors, including what the exception history
 * doesn't hold (assignments, notes). Both parameters are required: there is no "list everything" in
 * v1, which would be a reporting feature with its own paging and export concerns.
 *
 * <p>Lives in the audit module, which owns the table; the exceptions module has no part in it.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
class AuditEventController {

    /**
     * One event. {@code actorId} is null for system actions (the engine, workers). Ids only, no
     * display names: {@code audit} depends on {@code shared} alone (architecture.md §4), and
     * {@code security} depends on {@code audit}, so asking security's directory would be a cycle.
     * {@code requestId} links the event to the logs of the request that caused it.
     */
    record AuditEventView(
            UUID id,
            AuditAction action,
            @Nullable UUID actorId,
            Map<String, Object> payload,
            @Nullable String requestId,
            Instant at) {

        static AuditEventView of(AuditEvent e) {
            return new AuditEventView(
                    e.getId(), e.getAction(), e.getActorId(), e.getPayload(), e.getRequestId(), e.getCreatedAt());
        }
    }

    private final AuditTimeline timeline;

    AuditEventController(AuditTimeline timeline) {
        this.timeline = timeline;
    }

    @GetMapping
    @RequiresPermission(Permission.AUDIT_READ)
    PagedModel<AuditEventView> timeline(
            @RequestParam AuditEntityType entityType,
            @RequestParam UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) @Nullable List<String> sort) {
        Pagination.requireNoSort(sort, "createdAt, id");
        return new PagedModel<>(timeline.of(entityType, entityId, page, size).map(AuditEventView::of));
    }
}
