package com.reconflow.paymentreconflow.exceptions.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Cases, always for one organization (M3.5): {@link Repository}, not JpaRepository, so there is no
 * inherited {@code findById} that ignores the tenant. No {@code save}: cases are created in SQL by
 * DiscrepancyRecorder, and a loaded case is managed, so its changes are flushed by dirty checking.
 */
public interface ExceptionCaseRepository extends Repository<ExceptionCase, UUID> {

    Optional<ExceptionCase> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The OPEN or IN_REVIEW case with this key, if any: what a reopen would collide with (M7.3). */
    @Query(
            "select c.id from ExceptionCase c where c.organizationId = :organizationId"
                    + " and c.discrepancyKey = :key and c.status <> com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.RESOLVED")
    Optional<UUID> findActiveIdByOrganizationIdAndKey(
            @Param("organizationId") UUID organizationId, @Param("key") String key);
}
