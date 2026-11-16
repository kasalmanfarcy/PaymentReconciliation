package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.shared.jobs.Job;
import com.reconflow.paymentreconflow.shared.jobs.JobContext;
import com.reconflow.paymentreconflow.shared.jobs.JobHandler;
import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Plugs reconciliation runs into the generic queue: the queue knows nothing about runs.
 *
 * <p>Worker actions have no HTTP request, so their audit rows carry {@code job:<jobId>} as the
 * request id (phase-M6 §M6.13): the timeline of a run links back to the job that did the work.
 */
@Component
class ReconcileJobHandler implements JobHandler {

    private final RunExecutor executor;
    private final RunLifecycle lifecycle;

    ReconcileJobHandler(RunExecutor executor, RunLifecycle lifecycle) {
        this.executor = executor;
        this.lifecycle = lifecycle;
    }

    @Override
    public String type() {
        return ReconcileJobs.TYPE;
    }

    @Override
    public void handle(Job job, JobContext context) {
        withJobRequestId(job, () -> executor.execute(job, context));
    }

    /** In the queue's transaction that also marks the job FAILED, so the two always agree. */
    @Override
    public void onPermanentFailure(Job job, String reason) {
        withJobRequestId(job, () -> lifecycle.fail(job.organizationId(), job.uuid(ReconcileJobs.RUN_ID), reason));
    }

    private static void withJobRequestId(Job job, Runnable work) {
        String previous = MDC.get(RequestIdFilter.MDC_KEY);
        MDC.put(RequestIdFilter.MDC_KEY, "job:" + job.id());
        try {
            work.run();
        } finally {
            if (previous == null) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            } else {
                MDC.put(RequestIdFilter.MDC_KEY, previous);
            }
        }
    }
}
