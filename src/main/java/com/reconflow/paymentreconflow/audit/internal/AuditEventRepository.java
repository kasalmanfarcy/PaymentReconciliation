package com.reconflow.paymentreconflow.audit.internal;

import com.reconflow.paymentreconflow.audit.AuditEntityType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Read-only. Extends {@link Repository}, not JpaRepository or CrudRepository, so there is no
 * save, delete or deleteAll to call by accident; writes go through AuditService (EntityManager).
 */
public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    /** An entity's timeline (M7.13); served by audit_event_entity_idx. */
    List<AuditEvent> findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(
            UUID organizationId, AuditEntityType entityType, UUID entityId);
}
