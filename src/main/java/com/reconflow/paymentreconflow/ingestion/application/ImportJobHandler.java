package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobContext;
import com.reconflow.paymentreconflow.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

/** Plugs imports into the generic queue: the queue knows nothing about batches. */
@Component
class ImportJobHandler implements JobHandler {

    private final ImportProcessor processor;
    private final ImportBatchRepository batches;
    private final ImportOutcomes outcomes;

    ImportJobHandler(ImportProcessor processor, ImportBatchRepository batches, ImportOutcomes outcomes) {
        this.processor = processor;
        this.batches = batches;
        this.outcomes = outcomes;
    }

    @Override
    public String type() {
        return ImportJobs.TYPE;
    }

    @Override
    public void handle(Job job, JobContext context) {
        processor.process(job, context);
    }

    /** In the queue's transaction that also marks the job FAILED, so the two always agree. */
    @Override
    public void onPermanentFailure(Job job, String reason) {
        batches.lockByIdAndOrganizationId(job.uuid(ImportJobs.BATCH_ID), job.organizationId())
                .ifPresent(batch -> outcomes.failed(batch, reason, job));
    }
}
