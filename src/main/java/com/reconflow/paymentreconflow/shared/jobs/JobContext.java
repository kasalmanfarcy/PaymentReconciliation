package com.reconflow.paymentreconflow.shared.jobs;

/** What a running handler may ask of the queue. */
public interface JobContext {

    /**
     * Proves this worker still owns the job, and extends its lease, inside the caller's
     * transaction: {@code UPDATE job ... WHERE id = ? AND locked_by = ? AND status = 'RUNNING'}.
     * If the lease was lost (the worker paused past it and the sweeper gave the job to someone
     * else), zero rows match and {@link LeaseLostException} rolls the caller's whole transaction
     * back: a zombie can never commit (fencing).
     */
    void fence();

    /**
     * Marks the job DONE in the caller's transaction, fenced like {@link #fence()}, so the business
     * outcome and the job's completion commit together.
     */
    void complete();

    /**
     * Cheap, in-memory: throws {@link LeaseLostException} if the background heartbeat has found
     * that this worker no longer holds the job. Call it between rows or statements, so a worker
     * that lost its lease stops at once instead of building a whole chunk it could never commit
     * (the chunk's {@link #fence()} would refuse it anyway; this only saves the wasted work).
     */
    void ensureLeaseHeld();

    /**
     * True once this worker is shutting down. Check it between chunks and throw
     * {@link JobStoppedException}; the queue then hands the job back without spending an attempt.
     */
    boolean stopRequested();
}
