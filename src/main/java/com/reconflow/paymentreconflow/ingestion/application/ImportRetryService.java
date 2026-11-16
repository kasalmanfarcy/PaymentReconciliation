package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ingestion.domain.InvalidTransitionException;
import com.reconflow.paymentreconflow.ingestion.domain.RowErrorWriter;
import com.reconflow.paymentreconflow.ledger.TransactionRecordWriter;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.jobs.JobQueue;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /imports/{id}/retry} (M5.20): reprocess a FAILED batch from scratch, with an audited
 * reason. The stored file is reused; re-uploading isn't possible anyway, the hash is taken (M5.9).
 *
 * <p>Why delete and start over rather than resume from the checkpoint: a retry usually follows a
 * code fix, and the fixed code may validate or normalize differently. Mixing two code versions'
 * rows in one batch would be inconsistent, and the row key can't catch every overlap (M5.5).
 * Deleting is safe because a FAILED batch's rows were never accepted (rule P3): no run used them.
 * The original file and the audit trail are untouched; the old job stays FAILED for history.
 */
@Service
public class ImportRetryService {

    private final ImportBatchRepository batches;
    private final TransactionRecordWriter records;
    private final RowErrorWriter errors;
    private final JobQueue jobs;
    private final AuditService audit;
    private final TenantContext tenant;
    private final Clock clock;

    ImportRetryService(
            ImportBatchRepository batches,
            TransactionRecordWriter records,
            RowErrorWriter errors,
            JobQueue jobs,
            AuditService audit,
            TenantContext tenant,
            Clock clock) {
        this.batches = batches;
        this.records = records;
        this.errors = errors;
        this.jobs = jobs;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * All in one transaction, with the batch row locked: two users retrying at once are serialized,
     * and the second finds the batch RECEIVED and gets 409.
     */
    @Transactional
    public ImportView retry(UUID id, String reason) {
        UUID organizationId = tenant.organizationId();
        ImportBatch batch = batches.lockByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new NotFoundException("Import", id));
        if (batch.getStatus() != ImportBatchStatus.FAILED) {
            throw new InvalidTransitionException(id, batch.getStatus(), ImportBatchStatus.RECEIVED);
        }
        String previousReason = batch.getFailureReason();
        records.deleteBatch(organizationId, id);
        errors.deleteBatch(organizationId, id);
        batch.resetForRetry(clock.instant());
        jobs.enqueue(organizationId, ImportJobs.TYPE, ImportJobs.payload(id));
        audit.record(AuditEntry.of(AuditAction.IMPORT_RETRY_REQUESTED, AuditEntityType.IMPORT_BATCH, id)
                .with("previousFailureReason", previousReason)
                .with("reason", reason));
        return ImportView.of(batch, 0);
    }
}
