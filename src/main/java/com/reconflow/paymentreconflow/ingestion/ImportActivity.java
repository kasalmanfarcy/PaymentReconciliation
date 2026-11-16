package com.reconflow.paymentreconflow.ingestion;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one question {@code reconciliation} asks {@code ingestion} (architecture.md §4): is an import
 * of this organization still being received or processed? A run over a half-imported gateway file
 * would report every payment missing, so a run isn't requested (409 import-in-progress) or started
 * (the job is deferred) while one is.
 *
 * <p>Public API of the ingestion module, in its top-level package; the repository behind it stays
 * internal.
 */
@Service
public class ImportActivity {

    private final ImportBatchRepository batches;

    ImportActivity(ImportBatchRepository batches) {
        this.batches = batches;
    }

    @Transactional(readOnly = true)
    public boolean importInProgress(UUID organizationId) {
        return batches.existsByOrganizationIdAndStatusIn(
                organizationId, EnumSet.of(ImportBatchStatus.RECEIVED, ImportBatchStatus.PROCESSING));
    }
}
