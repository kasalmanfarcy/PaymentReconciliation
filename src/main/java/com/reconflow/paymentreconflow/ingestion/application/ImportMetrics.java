package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.jobs.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Import metrics (M9.9). Tags have a small fixed set of values ({@code source_type},
 * {@code outcome}): never an organization, batch or user id, which would make a new time series per
 * upload, forever (a cardinality explosion). "Which tenant is slow?" is answered from the logs and
 * the audit trail, which carry the ids.
 */
@Component
class ImportMetrics {

    private final MeterRegistry registry;

    ImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** After a chunk's transaction commits: its valid and rejected rows. */
    void rows(SourceType source, int valid, int rejected) {
        AfterCommit.run(() -> {
            registry.counter("reconflow.import.rows", "source_type", source.name(), "outcome", "valid")
                    .increment(valid);
            registry.counter("reconflow.import.rows", "source_type", source.name(), "outcome", "rejected")
                    .increment(rejected);
        });
    }

    /** A batch reached a final state; {@code took} is its processing time (started → completed). */
    void finished(SourceType source, ImportBatchStatus status, Duration took) {
        AfterCommit.run(() -> Timer.builder("reconflow.import.duration")
                .description("Import processing time, from start to a final state")
                .tag("source_type", source.name())
                .tag("outcome", status.name().toLowerCase(Locale.ROOT))
                // Histogram buckets, not client-side percentiles: buckets add up across instances,
                // so Prometheus can compute a p95 over the whole fleet (phase-M9 §2.5).
                .publishPercentileHistogram()
                .register(registry)
                .record(took));
    }
}
