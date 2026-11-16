package com.reconflow.paymentreconflow.audit.internal;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.shared.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * One row of the append-only audit log. Internal to the audit module: only AuditService creates
 * one, and there is no setter, no update method and no repository write method.
 *
 * <p>{@link Immutable}: Hibernate never dirty-checks or updates it, so an accidental field change
 * can't become an UPDATE (which the V1 trigger would reject anyway, at commit time, far from the
 * bug).
 */
@Entity
@Table(name = "audit_event")
@Immutable
public class AuditEvent implements Persistable<UUID> {

    @Id
    private UUID id;

    // NULL for platform events (logins, failed logins for unknown emails).
    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    // NULL for system actions (workers, sweepers, unknown-email login failures).
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false)
    private AuditEntityType entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    // Hibernate 7 maps SqlTypes.JSON with the Jackson on the classpath (Jackson 3 under Boot 4).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    // DB default now(): the transaction's start time, shared by every event of one transaction.
    // Order by (created_at, id); UUIDv7 ids break the tie in insertion order.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected AuditEvent() {} // JPA

    public static AuditEvent of(
            @Nullable UUID organizationId,
            @Nullable UUID actorId,
            AuditAction action,
            AuditEntityType entityType,
            @Nullable UUID entityId,
            Map<String, Object> payload,
            @Nullable String requestId) {
        var e = new AuditEvent();
        e.id = Ids.newId();
        e.organizationId = organizationId;
        e.actorId = actorId;
        e.action = action;
        e.entityType = entityType;
        e.entityId = entityId;
        e.payload = Map.copyOf(payload);
        e.requestId = requestId;
        e.isNew = true;
        return e;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    public @Nullable UUID getOrganizationId() {
        return organizationId;
    }

    public @Nullable UUID getActorId() {
        return actorId;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditEntityType getEntityType() {
        return entityType;
    }

    public @Nullable UUID getEntityId() {
        return entityId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public @Nullable String getRequestId() {
        return requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
