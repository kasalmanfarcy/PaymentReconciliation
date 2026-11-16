package com.reconflow.paymentreconflow.shared.jobs;

/**
 * How a module plugs work into the queue: one handler per job type ({@code IMPORT} from
 * ingestion, {@code RECONCILE} from M6). The queue knows nothing about imports or runs.
 *
 * <p>Handlers must be <strong>idempotent</strong>: a job can run more than once (a crash, an
 * expired lease, a retry), so a handler resumes from its own checkpoint, relies on unique
 * constraints, and calls {@link JobContext#fence()} in every transaction that writes.
 */
public interface JobHandler {

    /** The {@code job.type} this handler runs; one of the values in the table's CHECK. */
    String type();

    /**
     * Does the work, bound to the job's organization (so row-level security applies), outside any
     * transaction: the handler opens its own, one per chunk. Throw {@link TransientJobFailure} for
     * something a later attempt may get past, {@link PermanentJobFailure} for something it won't.
     * Any other exception counts as permanent (a bug fails the same way every time).
     */
    void handle(Job job, JobContext context);

    /**
     * The job won't be run again (a permanent failure, or attempts exhausted): record that in the
     * business data, e.g. mark the batch FAILED. Runs inside the transaction that also marks the
     * job FAILED, bound to the job's organization, so the two can't disagree.
     */
    void onPermanentFailure(Job job, String reason);
}
