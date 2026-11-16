package com.reconflow.paymentreconflow.shared.jobs;

import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims and runs jobs, classifies failures, and recovers expired leases. The worker (JobWorker,
 * worker nodes only) drives it from a thread pool on a schedule; tests drive it synchronously with
 * {@link #runAllQueued}, so they read "upload, run the queue, assert" with no sleeping.
 *
 * <p>The claim is its own short statement, never part of the work's transaction: a lock held for a
 * whole import would hide the job from the sweeper, and a dead worker's job could never be
 * recovered. The work then runs in transactions of its own (one per chunk), each fenced.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    static final String INTERNAL_ERROR = "internal-error";

    private final JobStore store;
    private final JobProperties props;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final JobMetrics metrics;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** Jobs whose heartbeat found the lease gone; their handlers stop at the next check. */
    private final Set<UUID> lostLeases = ConcurrentHashMap.newKeySet();

    private final String node;

    JobRunner(
            JobStore store,
            JobProperties props,
            TenantScope tenantScope,
            TransactionTemplate tx,
            JobMetrics metrics,
            ObjectProvider<JobHandler> handlers) {
        this.store = store;
        this.props = props;
        this.tenantScope = tenantScope;
        this.tx = tx;
        this.metrics = metrics;
        for (JobHandler h : handlers.orderedStream().toList()) {
            if (this.handlers.put(h.type(), h) != null) {
                throw new IllegalStateException("Two job handlers for type " + h.type());
            }
        }
        this.node = hostname() + ":" + ManagementFactory.getRuntimeMXBean().getPid();
    }

    /** {@code host:pid:<slot>}: exactly which worker holds a job, as stored in {@code locked_by}. */
    public String workerId(String slot) {
        return node + ":" + slot;
    }

    /**
     * Claims and runs queued jobs on the calling thread until none is runnable; returns how many ran.
     * A job rescheduled into the future (transient failure) is not runnable, so this terminates.
     */
    public int runAllQueued() {
        return runAllQueued(null);
    }

    /** As {@link #runAllQueued()}, but only for these organizations (tests share one database). */
    public int runAllQueued(@Nullable Collection<UUID> organizations) {
        String workerId = workerId(Thread.currentThread().getName());
        int ran = 0;
        Optional<Job> job;
        // Like the worker, never claims while stopping: a released job is runnable again at once.
        while (!stopping.get() && (job = claim(workerId, organizations)).isPresent()) {
            execute(job.get());
            ran++;
        }
        return ran;
    }

    /**
     * Claims the oldest runnable job for {@code workerId}, optionally only among some organizations
     * (tests share one database). The worker calls this per free slot; tests call it to play two
     * workers at once (a zombie and its successor).
     */
    public Optional<Job> claim(String workerId, @Nullable Collection<UUID> organizations) {
        return store.claim(workerId, props.lease(), props.perOrgMaxRunning(), organizations)
                .map(c -> {
                    metrics.claimed(c.job().type(), c.waited());
                    return c.job();
                });
    }

    /** Runs one claimed job to an outcome: DONE, re-queued with backoff, FAILED, released, or abandoned. */
    public void execute(Job job) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Jobs run outside any transaction; each chunk opens its own");
        }
        MDC.put("jobId", job.id().toString());
        MDC.put("orgId", job.organizationId().toString());
        try {
            JobHandler handler = handlers.get(job.type());
            if (handler == null) {
                log.error("No handler for job type {}; failing job {}", job.type(), job.id());
                store.markFailed(job.id(), job.workerId(), "no handler for type " + job.type());
                return;
            }
            // Bound before any transaction starts, so every transaction the handler opens sets
            // app.org_id for row-level security, exactly as a request would.
            tenantScope.run(job.organizationId(), () -> runBound(job, handler));
        } finally {
            lostLeases.remove(job.id());
            MDC.remove("jobId");
            MDC.remove("orgId");
        }
    }

    private void runBound(Job job, JobHandler handler) {
        try {
            handler.handle(job, new Context(job));
            store.markDone(job.id(), job.workerId()); // no-op if the handler already completed it
            metrics.completed(job.type(), "done");
        } catch (LeaseLostException e) {
            // Someone else owns the job now; whatever this worker had in flight was rolled back.
            metrics.leaseLost(job.type());
            log.warn("Lease lost on job {}; another worker owns it now", job.id());
        } catch (JobDeferredException e) {
            store.defer(job.id(), job.workerId(), e.delay(), e.getMessage());
            log.info("Deferred job {} by {}: {}", job.id(), e.delay(), e.getMessage());
        } catch (JobStoppedException e) {
            store.release(job.id(), job.workerId());
            log.info("Released job {} on shutdown", job.id());
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                if (job.attempts() >= job.maxAttempts()) {
                    failPermanently(job, handler, "retries-exhausted", e);
                } else {
                    var delay = Backoff.delay(
                            job.attempts(), props.baseBackoff(), props.maxBackoff(), ThreadLocalRandom.current());
                    store.reschedule(job.id(), job.workerId(), delay, describe(e));
                    metrics.completed(job.type(), "retried");
                    log.warn(
                            "Job {} failed transiently (attempt {}), retrying in {}",
                            job.id(),
                            job.attempts(),
                            delay,
                            e);
                }
            } else {
                // Permanent, including bugs: retrying a deterministic failure five times only delays
                // the report. After a fix, an operator retries explicitly.
                String reason = e instanceof PermanentJobFailure ? e.getMessage() : INTERNAL_ERROR;
                failPermanently(job, handler, reason, e);
            }
        }
    }

    /** The handler's clean-up and the job's FAILED status in one transaction, fenced. */
    private void failPermanently(Job job, JobHandler handler, String reason, Exception cause) {
        log.error("Job {} failed permanently: {}", job.id(), reason, cause);
        metrics.completed(job.type(), "failed");
        try {
            tx.executeWithoutResult(s -> {
                handler.onPermanentFailure(job, reason);
                if (!store.markFailed(job.id(), job.workerId(), describe(cause))) {
                    throw new LeaseLostException(job.id(), job.workerId());
                }
            });
        } catch (LeaseLostException e) {
            log.warn("Lease lost on job {} while failing it; the new owner decides", job.id());
        }
    }

    /** The same classifier as the in-process layer ({@link TransientFailures}), M9.3. */
    static boolean isTransient(Throwable e) {
        return TransientFailures.isTransient(e);
    }

    /**
     * Extends the lease of a job this worker is running (the heartbeat). If the lease is gone (this
     * worker paused past it and the sweeper gave the job away), the job's handler is told to stop
     * at its next {@link JobContext#ensureLeaseHeld()}.
     */
    boolean heartbeat(UUID jobId, String workerId) {
        boolean held = store.extendLease(jobId, workerId, props.lease());
        if (!held) {
            lostLeases.add(jobId);
        }
        return held;
    }

    /**
     * Recovers jobs whose worker died: expired leases with attempts left go back to the queue (the
     * next worker resumes from the handler's checkpoint); those with none left are failed, with the
     * handler told, one transaction each. Safe on several nodes at once: the requeue is one
     * idempotent statement and each failure re-checks its job under {@code FOR UPDATE SKIP LOCKED}.
     */
    public int sweepExpiredLeases() {
        List<UUID> requeued = store.requeueExpired();
        if (!requeued.isEmpty()) {
            log.warn("Re-queued {} job(s) whose lease expired: {}", requeued.size(), requeued);
        }
        int failed = 0;
        for (Job job : store.expiredExhausted()) {
            JobHandler handler = handlers.get(job.type());
            Boolean done = tenantScope.call(
                    job.organizationId(),
                    () -> tx.execute(s -> {
                        if (!store.lockExpiredExhausted(job.id())) {
                            return false; // another sweeper has it, or it recovered
                        }
                        if (handler != null) {
                            handler.onPermanentFailure(job, "retries-exhausted");
                        }
                        store.markFailedBySweeper(job.id(), "lease expired with no attempts left");
                        return true;
                    }));
            if (Boolean.TRUE.equals(done)) {
                metrics.completed(job.type(), "failed");
                failed++;
            }
        }
        return requeued.size() + failed;
    }

    /** Graceful shutdown: running handlers see {@link JobContext#stopRequested()} at their next chunk. */
    void requestStop() {
        stopping.set(true);
    }

    void clearStop() {
        stopping.set(false);
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private final class Context implements JobContext {

        private final Job job;

        Context(Job job) {
            this.job = job;
        }

        @Override
        public void fence() {
            requireTransaction();
            if (!store.extendLease(job.id(), job.workerId(), props.lease())) {
                throw new LeaseLostException(job.id(), job.workerId());
            }
        }

        @Override
        public void complete() {
            requireTransaction();
            if (!store.markDone(job.id(), job.workerId())) {
                throw new LeaseLostException(job.id(), job.workerId());
            }
        }

        @Override
        public void ensureLeaseHeld() {
            if (lostLeases.contains(job.id())) {
                throw new LeaseLostException(job.id(), job.workerId());
            }
        }

        @Override
        public boolean stopRequested() {
            return stopping.get();
        }

        private static void requireTransaction() {
            // A fence outside the chunk's transaction proves nothing: the data would commit alone.
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("fence() and complete() must run inside the work's transaction");
            }
        }
    }
}
