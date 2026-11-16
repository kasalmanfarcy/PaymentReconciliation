package com.reconflow.paymentreconflow.reporting.export;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobContext;
import com.reconflow.paymentreconflow.shared.jobs.JobHandler;
import com.reconflow.paymentreconflow.shared.jobs.TransientJobFailure;
import com.reconflow.paymentreconflow.shared.storage.FileStorage;
import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs large exports in the worker (M8.4): the same {@link ExceptionCsvExporter} as the streaming
 * path writes into a temporary file, the file goes to object storage under a key named after the
 * job, and the job is marked DONE with its audit event in one fenced transaction.
 *
 * <p>Idempotent, as every handler must be: a retry rewrites the same object (the key is the job
 * id), and nothing is committed before the final transaction. A temporary file rather than
 * streaming straight into S3: a single PUT needs the length up front, and a local file keeps the
 * database cursor's lifetime independent of the network to storage.
 */
@Component
class ExportJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ExportJobHandler.class);

    private final ExceptionCsvExporter exporter;
    private final FileStorage storage;
    private final AuditService audit;
    private final TransactionTemplate tx;

    ExportJobHandler(ExceptionCsvExporter exporter, FileStorage storage, AuditService audit, TransactionTemplate tx) {
        this.exporter = exporter;
        this.storage = storage;
        this.audit = audit;
        this.tx = tx;
    }

    @Override
    public String type() {
        return ExceptionExports.JOB_TYPE;
    }

    @Override
    public void handle(Job job, JobContext context) {
        UUID orgId = job.organizationId();
        UUID runId = job.uuid(ExceptionExports.RUN_ID);
        UUID requestedBy = job.uuid(ExceptionExports.REQUESTED_BY);
        String key = ExportKeys.file(orgId, job.id());
        withJobRequestId(job, () -> {
            Path file = null;
            try {
                file = Files.createTempFile("reconflow-export-", ".csv");
                long rows;
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
                    rows = exporter.write(orgId, runId, out);
                }
                try (InputStream in = Files.newInputStream(file)) {
                    storage.put(key, in, Files.size(file), "text/csv; charset=UTF-8");
                } catch (RuntimeException e) {
                    // Storage hiccups (a 503, a timeout) pass; the retry rewrites the same key.
                    throw new TransientJobFailure("storage-unavailable", e);
                }
                tx.executeWithoutResult(s -> {
                    context.fence();
                    // The person who asked is the actor, even though the worker did the work.
                    audit.record(
                            AuditEntry.of(AuditAction.EXCEPTIONS_EXPORTED, AuditEntityType.RECONCILIATION_RUN, runId)
                                    .with("mode", "ASYNC")
                                    .with("jobId", job.id())
                                    .with("rowCount", rows)
                                    .actorId(requestedBy));
                    context.complete();
                });
                log.info("Export {} of run {} wrote {} rows to {}", job.id(), runId, rows, key);
            } catch (IOException e) {
                throw new TransientJobFailure("export-file-io", e);
            } finally {
                deleteQuietly(file);
            }
        });
    }

    /** Nothing to mark: the job's own FAILED status is what the export status endpoint shows. */
    @Override
    public void onPermanentFailure(Job job, String reason) {
        log.warn("Export {} failed permanently: {}", job.id(), reason);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary export file {}", file, e);
        }
    }

    /** Worker actions have no HTTP request: their audit rows carry {@code job:<jobId>} (as M6's runs). */
    private interface IoWork {
        void run() throws IOException;
    }

    private static void withJobRequestId(Job job, IoWork work) {
        String previous = MDC.get(RequestIdFilter.MDC_KEY);
        MDC.put(RequestIdFilter.MDC_KEY, "job:" + job.id());
        try {
            work.run();
        } catch (IOException e) {
            throw new TransientJobFailure("export-file-io", e);
        } finally {
            if (previous == null) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            } else {
                MDC.put(RequestIdFilter.MDC_KEY, previous);
            }
        }
    }
}
