package com.reconflow.paymentreconflow.organization;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates, renames, suspends and lists organizations. Platform-level operations only. */
@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final EntityManager em;
    private final AuditService audit;

    OrganizationService(OrganizationRepository organizations, EntityManager em, AuditService audit) {
        this.organizations = organizations;
        this.em = em;
        this.audit = audit;
    }

    /**
     * MANDATORY: creating an organization is always one step of a larger change (its first admin,
     * its audit row), so it must join the caller's transaction rather than commit on its own.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Organization create(UUID id, String name, @Nullable UUID createdBy) {
        Organization org = organizations.save(Organization.createWithId(id, name));
        audit.record(AuditEntry.of(AuditAction.ORGANIZATION_CREATED, AuditEntityType.ORGANIZATION, id)
                .organizationId(id)
                .actorId(createdBy)
                .with("name", name));
        // Insert now and read back the database defaults (created_at) for the response.
        em.flush();
        em.refresh(org);
        return org;
    }

    @Transactional
    public Organization rename(UUID id, String newName) {
        Organization org = get(id);
        org.rename(newName);
        return org;
    }

    @Transactional
    public Organization suspend(UUID id) {
        Organization org = get(id);
        org.suspend();
        return org;
    }

    @Transactional(readOnly = true)
    public Organization get(UUID id) {
        return organizations.findById(id).orElseThrow(() -> new NotFoundException("Organization", id));
    }

    @Transactional(readOnly = true)
    public Page<Organization> list(Pageable pageable) {
        return organizations.findAll(pageable);
    }
}
