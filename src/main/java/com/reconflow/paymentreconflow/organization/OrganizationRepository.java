package com.reconflow.paymentreconflow.organization;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The one repository allowed to extend {@link JpaRepository}: {@code organization} is the tenant
 * itself, not tenant-owned, so there is no {@code organizationId} to scope by. Only platform-mode
 * code, the tenant filter and fixtures use it.
 */
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {}
