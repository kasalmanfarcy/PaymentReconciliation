package com.reconflow.paymentreconflow.reporting.export;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ConflictException;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.jobs.JobQueue;
import com.reconflow.paymentreconflow.shared.jobs.JobStatus;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides how a run's exceptions are exported (M8.4): streamed in the response up to the limit,
 * or by a background EXPORT job into object storage above it. Either way the export is audited:
 * it is financial data leaving the system.
 */
@Service
public class ExceptionExports {

    public static final String JOB_TYPE = "EXPORT";
    static final String RUN_ID = "runId";
    static final String REQUESTED_BY = "requestedBy";

    private final ExportQueries queries;
    private final ExportLimit limit;
    private final JobQueue jobs;
    private final AuditService audit;
    private final TenantContext tenant;
    private final CurrentUser currentUser;

    ExceptionExports(
            ExportQueries queries,
            ExportLimit limit,
            JobQueue jobs,
            AuditService audit,
            TenantContext tenant,
            CurrentUser currentUser) {
        this.queries = queries;
        this.limit = limit;
        this.jobs = jobs;
        this.audit = audit;
        this.tenant = tenant;
        this.currentUser = currentUser;
    }

    /** What the controller should do. */
    public sealed interface Plan {

        /** Stream it now. The tenant and run are captured here: the stream runs on another thread. */
        record Stream(UUID organizationId, UUID runId, long rows) implements Plan {}

        /** A job will write it; poll the export. */
        record Queued(ExportView export) implements Plan {}
    }

    /**
     * Counts the run's exceptions and picks the path, auditing in the same transaction as the
     * decision (and, for the background path, as the job it enqueues: a job never exists without
     * its audit event, nor the other way round).
     *
     * @throws NotFoundException unknown run, or another organization's
     */
    @Transactional
    public Plan request(UUID runId) {
        UUID orgId = tenant.organizationId();
        if (!queries.runExists(orgId, runId)) {
            throw new NotFoundException("Run", runId);
        }
        long rows = queries.countForRun(orgId, runId);
        if (rows <= limit.syncMaxRows(orgId)) {
            // Recorded when the download starts, with the count taken now: a client that drops the
            // connection half way has still been given data, so the event is not conditional on
            // the stream finishing.
            audit.record(AuditEntry.of(AuditAction.EXCEPTIONS_EXPORTED, AuditEntityType.RECONCILIATION_RUN, runId)
                    .with("mode", "SYNC")
                    .with("rowCount", rows));
            return new Plan.Stream(orgId, runId, rows);
        }
        UUID jobId = jobs.enqueue(
                orgId,
                JOB_TYPE,
                Map.of(RUN_ID, runId.toString(), REQUESTED_BY, currentUser.id().toString()));
        audit.record(AuditEntry.of(AuditAction.EXPORT_REQUESTED, AuditEntityType.RECONCILIATION_RUN, runId)
                .with("jobId", jobId)
                .with("rowCount", rows));
        return new Plan.Queued(view(jobs.find(orgId, jobId).orElseThrow()));
    }

    /** A background export of this organization; 404 for an unknown id, another tenant's or another job type. */
    @Transactional(readOnly = true)
    public ExportView status(UUID exportId) {
        return view(find(exportId));
    }

    /**
     * The storage key of a finished export.
     *
     * @throws ConflictException {@code export-not-ready} until the job is DONE
     */
    @Transactional(readOnly = true)
    public String fileKey(UUID exportId) {
        JobStatus job = find(exportId);
        if (!job.status().equals("DONE")) {
            throw new ConflictException(
                    ProblemCodes.EXPORT_NOT_READY,
                    "The export is " + job.status() + "; download it once it is DONE.",
                    Map.of("currentStatus", job.status()));
        }
        return ExportKeys.file(job.organizationId(), job.id());
    }

    private JobStatus find(UUID exportId) {
        return jobs.find(tenant.organizationId(), exportId)
                .filter(j -> j.type().equals(JOB_TYPE))
                .orElseThrow(() -> new NotFoundException("Export", exportId));
    }

    private static ExportView view(JobStatus job) {
        return new ExportView(
                job.id(),
                UUID.fromString(String.valueOf(job.payload().get(RUN_ID))),
                job.status(),
                job.createdAt(),
                job.updatedAt(),
                job.status().equals("DONE") ? "/api/v1/reports/exports/" + job.id() + "/file" : null);
    }
}
