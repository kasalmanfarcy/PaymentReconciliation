package com.reconflow.paymentreconflow.shared.jobs;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background worker: polls the queue, runs jobs on a bounded pool, heartbeats their leases and
 * sweeps expired ones (ADR 0004). Exists only where {@code reconflow.jobs.worker.enabled=true},
 * which the {@code worker} profile sets; a property rather than {@code @Profile} keeps profile
 * names in YAML. The {@code @Scheduled} methods run because WorkerSchedulingConfig enables
 * scheduling under the same condition.
 *
 * <p>Each pool thread is a fixed "slot" with its own worker id ({@code host:pid:slot-N}), so
 * {@code locked_by} names exactly which thread holds a job. The poller only claims while a slot is
 * free: the queue, not an in-memory backlog, holds waiting work, so a crash loses nothing.
 *
 * <p>Not {@code @Async}: its tasks live in memory and vanish on restart.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.jobs.worker.enabled")
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 25;

    private final JobRunner runner;
    private final JobWorkerProperties workerProps;
    private final BlockingQueue<String> freeSlots;
    private final Map<UUID, String> inFlight = new ConcurrentHashMap<>();
    private volatile ExecutorService executor;
    private volatile boolean running;

    JobWorker(JobRunner runner, JobWorkerProperties workerProps) {
        this.runner = runner;
        this.workerProps = workerProps;
        this.freeSlots = new ArrayBlockingQueue<>(workerProps.threads());
    }

    @Override
    public void start() {
        freeSlots.clear();
        for (int i = 1; i <= workerProps.threads(); i++) {
            freeSlots.add("slot-" + i);
        }
        executor = Executors.newFixedThreadPool(
                workerProps.threads(),
                Thread.ofPlatform().name("job-worker-", 1).factory());
        runner.clearStop();
        running = true;
        log.info("Job worker started with {} threads", workerProps.threads());
    }

    /** Claims while a slot is free; each claim is one short statement in its own transaction. */
    @Scheduled(fixedDelayString = "${reconflow.jobs.poll-interval:1s}")
    void poll() {
        String slot;
        while (running && (slot = freeSlots.poll()) != null) {
            String workerId = runner.workerId(slot);
            Optional<Job> job;
            try {
                job = runner.claim(workerId, null);
            } catch (RuntimeException e) {
                freeSlots.add(slot);
                log.warn("Claim failed; will retry on the next poll", e);
                return;
            }
            if (job.isEmpty()) {
                freeSlots.add(slot);
                return;
            }
            Job claimed = job.get();
            inFlight.put(claimed.id(), workerId);
            String freed = slot;
            executor.execute(() -> {
                try {
                    runner.execute(claimed);
                } finally {
                    inFlight.remove(claimed.id());
                    freeSlots.add(freed);
                }
            });
        }
    }

    /** Extends every lease this JVM holds; a dead JVM stops doing this and its leases expire. */
    @Scheduled(fixedDelayString = "${reconflow.jobs.heartbeat:30s}")
    void heartbeat() {
        inFlight.forEach((jobId, workerId) -> {
            if (!runner.heartbeat(jobId, workerId)) {
                log.warn("Heartbeat: job {} is no longer leased to {}", jobId, workerId);
            }
        });
    }

    /**
     * Startup sweep (M9.1): recover what a crashed predecessor left behind at once, instead of
     * after the first scheduled tick. Only <em>expired</em> leases, never "jobs locked by my
     * hostname": a restarted container keeps its hostname, and other workers are alive and holding
     * valid leases. The worker id also carries the pid, so this process never owns an old job.
     */
    @EventListener(ApplicationReadyEvent.class)
    void sweepOnStartup() {
        int recovered = runner.sweepExpiredLeases();
        log.info("Startup sweep recovered {} job(s) with expired leases", recovered);
    }

    @Scheduled(fixedDelayString = "${reconflow.jobs.sweep-interval:30s}")
    void sweep() {
        runner.sweepExpiredLeases();
    }

    /**
     * Graceful shutdown: stop claiming, ask running handlers to stop at their next chunk boundary
     * (they hand their jobs back without spending an attempt, so another worker resumes at once),
     * and wait a bounded time. A job still running after that keeps its lease until it expires.
     */
    @Override
    public void stop() {
        running = false;
        runner.requestStop();
        ExecutorService pool = executor;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("{} job(s) still running at shutdown; their leases will expire", inFlight.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
