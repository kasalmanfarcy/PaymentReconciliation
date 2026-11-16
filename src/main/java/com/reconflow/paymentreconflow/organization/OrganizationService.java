package com.reconflow.paymentreconflow.organization;

import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates, renames, suspends and lists organizations. Platform-level operations only. */
@Service
public class OrganizationService {

    private final OrganizationRepository organizations;

    OrganizationService(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    /**
     * MANDATORY: creating an organization is always one step of a larger change (its first admin,
     * its audit row), so it must join the caller's transaction rather than commit on its own.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Organization create(UUID id, String name) {
        return organizations.save(Organization.createWithId(id, name));
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
