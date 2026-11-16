package com.reconflow.paymentreconflow.shared.jobs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job-queue metrics (M9.9). Tags are the job type and an outcome: fixed, small sets. The queued-jobs
 * gauge is not a query per scrape (several instances scraped every 15 s would put a steady load on
 * the database, and a slow count would stall the scrape): a scheduled refresh on worker nodes stores
 * the counts, and the gauge reads the stored value.
 */
@Component
public class JobMetrics {

    /** The production job types (V1's CHECK); a gauge per type, so a type with nothing queued reads 0. */
    static final List<String> TYPES = List.of("IMPORT", "RECONCILE", "EXPORT");

    private final MeterRegistry registry;
    private final JobStore store;
    private final Map<String, AtomicLong> queued = new ConcurrentHashMap<>();

    JobMetrics(MeterRegistry registry, JobStore store) {
        this.registry = registry;
        this.store = store;
        for (String type : TYPES) {
            AtomicLong value = new AtomicLong();
            queued.put(type, value);
            Gauge.builder("reconflow.jobs.queued", value, AtomicLong::get)
                    .description("Jobs waiting in the queue (refreshed every 30 s on worker nodes)")
                    .tag("type", type)
                    .register(registry);
        }
    }

    /** How long a job was runnable before a worker took it: {@code now() - run_after} at the claim. */
    void claimed(String type, Duration waited) {
        Timer.builder("reconflow.jobs.queue.wait")
                .description("Time a runnable job waited for a worker (SLO: p95 < 2 min at the 09:00 peak)")
                .tag("type", type)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5))
                .register(registry)
                .record(waited);
    }

    /** {@code done}, {@code retried} (re-queued after a transient failure) or {@code failed}. */
    void completed(String type, String outcome) {
        registry.counter("reconflow.jobs.completed", "type", type, "outcome", outcome)
                .increment();
    }

    /** A worker found its lease taken over (fencing, M9.1). Should always be 0: alert on any. */
    void leaseLost(String type) {
        registry.counter("reconflow.jobs.lease.lost", "type", type).increment();
    }

    @Scheduled(fixedDelayString = "${reconflow.jobs.metrics-interval:30s}")
    void refreshQueued() {
        Map<String, Long> counts = store.countQueuedByType();
        queued.forEach((type, value) -> value.set(counts.getOrDefault(type, 0L)));
    }
}
