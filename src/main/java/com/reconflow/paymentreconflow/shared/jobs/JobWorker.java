package com.reconflow.paymentreconflow.shared.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The background job worker. Placeholder until M5 adds the claim loop
 * ({@code FOR UPDATE SKIP LOCKED}), leases, heartbeats and the sweeper.
 *
 * <p>This is the only bean switched by role: it exists only when
 * {@code reconflow.jobs.worker.enabled=true}, which the {@code worker} profile sets. Using a
 * property instead of {@code @Profile("worker")} keeps profile names in YAML, and lets a test
 * or an operator turn the worker on or off without inventing a new profile.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.jobs.worker.enabled")
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobWorkerProperties properties;
    private volatile boolean running;

    JobWorker(JobWorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
        log.info("Job worker enabled with {} threads (no job handlers registered yet)", properties.threads());
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
