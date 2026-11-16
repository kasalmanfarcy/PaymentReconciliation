package com.reconflow.paymentreconflow.ingestion.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Reads a batch's row errors, always within one organization. Writes go through RowErrorWriter. */
public interface ImportRowErrorRepository extends Repository<ImportRowError, UUID> {

    /** Ordered by the caller's Pageable; the unique key (batch_id, row_number, ...) serves it. */
    Page<ImportRowError> findByOrganizationIdAndBatchId(UUID organizationId, UUID batchId, Pageable pageable);

    long countByOrganizationIdAndBatchId(UUID organizationId, UUID batchId);
}
