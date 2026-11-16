package com.reconflow.paymentreconflow.reconciliation.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Runs, always for one organization (M3.5): {@link Repository}, not JpaRepository, so there is no
 * inherited {@code findById} that would ignore the tenant.
 */
public interface ReconciliationRunRepository extends Repository<ReconciliationRun, UUID> {

    ReconciliationRun save(ReconciliationRun run);

    /** Flushes at once, so a unique violation (the active-scope index) surfaces here, not at commit. */
    ReconciliationRun saveAndFlush(ReconciliationRun run);

    Optional<ReconciliationRun> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<ReconciliationRun> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

    /**
     * {@code SELECT ... FOR UPDATE}: every status transition runs with the row locked, so two
     * transitions of one run (two retries, a retry and a worker) are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReconciliationRun r where r.id = :id and r.organizationId = :organizationId")
    Optional<ReconciliationRun> lockByIdAndOrganizationId(
            @Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
