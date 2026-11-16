package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.shared.context.TenantDirectory;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.shared.jobs.JobQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M9.1's consistency check: a batch {@code RECEIVED} or {@code PROCESSING} with no {@code QUEUED} or
 * {@code RUNNING} job will never move again. The job row is the source of truth for whether work is
 * alive (the batch has no lease of its own), and the enqueue is transactional, so this "can't
 * happen"; the check exists because "can't happen" is a claim, and a stuck batch blocks every run of
 * its organization (a run waits while an import is in flight).
 *
 * <p>Found orphans are logged at ERROR, counted ({@code reconflow.jobs.orphaned}) and failed with
 * {@code orphaned}, never re-enqueued automatically: an orphan means a bug in the enqueue path that
 * a person should look at, and the user can retry the batch explicitly. Every 5 minutes, worker
 * nodes only (scheduling is enabled only there); safe on several at once (each batch is re-checked
 * under its row lock).
 */
@Component
public class OrphanedImportCheck {

    /** Older than any chunk could take: a live import touches the batch with every chunk. */
    static final Duration MIN_AGE = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(OrphanedImportCheck.class);

    private final ImportBatchRepository batches;
    private final JobQueue jobs;
    private final ImportOutcomes outcomes;
    private final TenantDirectory tenants;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final Counter orphaned;

    OrphanedImportCheck(
            ImportBatchRepository batches,
            JobQueue jobs,
            ImportOutcomes outcomes,
            TenantDirectory tenants,
            TenantScope tenantScope,
            TransactionTemplate tx,
            MeterRegistry registry) {
        this.batches = batches;
        this.jobs = jobs;
        this.outcomes = outcomes;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.tx = tx;
        this.orphaned = Counter.builder("reconflow.jobs.orphaned")
                .description("Business rows found in flight with no live job (M9.1); should stay 0")
                .tag("type", ImportJobs.TYPE)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${reconflow.jobs.orphan-check-interval:5m}", initialDelayString = "1m")
    void checkAll() {
        check(tenants.organizationIds(), MIN_AGE);
    }

    /** Fails the orphaned batches of these organizations; returns their ids. */
    public List<UUID> check(Collection<UUID> organizations, Duration minAge) {
        double seconds = minAge.toMillis() / 1000.0;
        return organizations.stream()
                .flatMap(org -> tenantScope
                        .call(org, () -> {
                            List<UUID> candidates = tx.execute(s -> batches.findStaleInFlight(org, seconds));
                            return candidates.stream()
                                    .filter(id -> Boolean.TRUE.equals(tx.execute(s -> failIfOrphaned(org, id))))
                                    .toList();
                        })
                        .stream())
                .toList();
    }

    private boolean failIfOrphaned(UUID org, UUID batchId) {
        ImportBatch batch = batches.lockByIdAndOrganizationId(batchId, org).orElse(null);
        if (batch == null
                || batch.getStatus().isTerminal()
                || jobs.hasLiveJob(org, ImportJobs.TYPE, ImportJobs.BATCH_ID, batchId)) {
            return false;
        }
        log.error(
                "Batch {} of organization {} is {} with no live job: failing it as orphaned",
                batchId,
                org,
                batch.getStatus());
        outcomes.orphaned(batch);
        orphaned.increment();
        return true;
    }
}
