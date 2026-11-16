package com.reconflow.paymentreconflow.ingestion.domain;

import com.reconflow.paymentreconflow.ledger.SourceType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Batches, always for one organization (M3.5): {@link Repository}, not JpaRepository, so there is
 * no inherited {@code findById} that would ignore the tenant. The organization comes from
 * TenantContext, never from the request.
 */
public interface ImportBatchRepository extends Repository<ImportBatch, UUID> {

    ImportBatch save(ImportBatch batch);

    /** Flushes at once, so a unique violation surfaces here and not at commit (M5.9). */
    ImportBatch saveAndFlush(ImportBatch batch);

    Optional<ImportBatch> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /**
     * {@code SELECT ... FOR UPDATE}: every status transition runs with the row locked, so two
     * transitions of one batch (two retries, a retry and a worker) are serialized.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.id = :id and b.organizationId = :organizationId")
    Optional<ImportBatch> lockByIdAndOrganizationId(@Param("id") UUID id, @Param("organizationId") UUID organizationId);

    /** The duplicate-file fast path (M5.9), served by import_batch_content_uk. */
    Optional<ImportBatch> findByOrganizationIdAndSourceTypeAndContentHash(
            UUID organizationId, SourceType sourceType, String contentHash);
}
