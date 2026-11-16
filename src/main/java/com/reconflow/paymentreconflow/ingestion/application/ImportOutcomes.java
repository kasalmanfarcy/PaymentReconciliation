package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * How a batch ends (requirements.md §7.5), decided in one place, always inside the caller's
 * transaction with the batch row locked, and audited in that same transaction (M5.16): if the
 * outcome rolls back, so does its audit row.
 *
 * <p>These are system events: no user is bound in the worker, so the actor is NULL, and there is no
 * request ID, so the payload carries the job ID to link the timeline back to the job. Payloads hold
 * counts and reasons, never row contents or references: audit rows are financial-data-free and
 * live forever.
 */
@Component
public class ImportOutcomes {

    /** {@code failure_reason} when no row of the file was valid. */
    public static final String NO_VALID_ROWS = "no-valid-rows";

    private final AuditService audit;
    private final Clock clock;

    ImportOutcomes(AuditService audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Every row valid: COMPLETED. Some invalid: COMPLETED_WITH_ERRORS. None valid: FAILED, as
     * requirements.md §7.5 says (the phase plan said COMPLETED_WITH_ERRORS; the docs win).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finished(ImportBatch batch, int lastRow, Job job) {
        if (batch.getValidRows() == 0) {
            failed(batch, NO_VALID_ROWS, job);
            return;
        }
        batch.transitionTo(
                batch.getErrorRows() == 0 ? ImportBatchStatus.COMPLETED : ImportBatchStatus.COMPLETED_WITH_ERRORS,
                clock.instant());
        Duration took = batch.getStartedAt() == null
                ? Duration.ZERO
                : Duration.between(batch.getStartedAt(), batch.getCompletedAt());
        audit.record(AuditEntry.of(AuditAction.IMPORT_COMPLETED, AuditEntityType.IMPORT_BATCH, batch.getId())
                .with("status", batch.getStatus().name())
                .with("totalRows", batch.getTotalRows())
                .with("validRows", batch.getValidRows())
                .with("errorRows", batch.getErrorRows())
                .with("durationMs", took.toMillis())
                .with("jobId", job.id()));
    }

    /** The job won't run again: the batch fails with the reason users will see. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failed(ImportBatch batch, String reason, Job job) {
        if (batch.getStatus().isTerminal()) {
            return;
        }
        batch.fail(reason, clock.instant());
        audit.record(AuditEntry.of(AuditAction.IMPORT_FAILED, AuditEntityType.IMPORT_BATCH, batch.getId())
                .with("failureReason", reason)
                .with("attempts", job.attempts())
                .with("lastCommittedRow", batch.getLastCommittedRow())
                .with("jobId", job.id()));
    }
}
