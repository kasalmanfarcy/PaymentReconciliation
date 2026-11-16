package com.reconflow.paymentreconflow.reconciliation.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
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

    /**
     * Runs still PENDING or RUNNING that haven't changed for {@code minAgeSeconds}: candidates for
     * M9.1's consistency check. A live run touches {@code updated_at} with every day's checkpoint.
     */
    @Query(value = """
                    SELECT id FROM reconciliation_run
                     WHERE organization_id = :organizationId AND status IN ('PENDING', 'RUNNING')
                       AND updated_at < now() - make_interval(secs => :minAgeSeconds)
                    """, nativeQuery = true)
    List<UUID> findStaleInFlight(
            @Param("organizationId") UUID organizationId, @Param("minAgeSeconds") double minAgeSeconds);

    List<ReconciliationRun> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

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
