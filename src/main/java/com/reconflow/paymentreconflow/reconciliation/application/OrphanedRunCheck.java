package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRunRepository;
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
 * M9.1's consistency check for runs, the twin of ingestion's OrphanedImportCheck: a run
 * {@code PENDING} or {@code RUNNING} with no {@code QUEUED} or {@code RUNNING} job will never move
 * again, and it holds its scope's one-active-run slot, so no new run of that scope can start.
 * Logged at ERROR, counted, failed with {@code orphaned} through {@link RunLifecycle#fail} (audited);
 * never re-enqueued automatically. A user can retry it, resuming after its checkpoint.
 */
@Component
public class OrphanedRunCheck {

    public static final String ORPHANED = "orphaned";

    /** Older than any day-chunk could take: a live run touches the row with every checkpoint. */
    static final Duration MIN_AGE = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(OrphanedRunCheck.class);

    private final ReconciliationRunRepository runs;
    private final JobQueue jobs;
    private final RunLifecycle lifecycle;
    private final TenantDirectory tenants;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final Counter orphaned;

    OrphanedRunCheck(
            ReconciliationRunRepository runs,
            JobQueue jobs,
            RunLifecycle lifecycle,
            TenantDirectory tenants,
            TenantScope tenantScope,
            TransactionTemplate tx,
            MeterRegistry registry) {
        this.runs = runs;
        this.jobs = jobs;
        this.lifecycle = lifecycle;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.tx = tx;
        this.orphaned = Counter.builder("reconflow.jobs.orphaned")
                .description("Business rows found in flight with no live job (M9.1); should stay 0")
                .tag("type", ReconcileJobs.TYPE)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${reconflow.jobs.orphan-check-interval:5m}", initialDelayString = "1m")
    void checkAll() {
        check(tenants.organizationIds(), MIN_AGE);
    }

    /** Fails the orphaned runs of these organizations; returns their ids. */
    public List<UUID> check(Collection<UUID> organizations, Duration minAge) {
        double seconds = minAge.toMillis() / 1000.0;
        return organizations.stream()
                .flatMap(org -> tenantScope
                        .call(org, () -> {
                            List<UUID> candidates = tx.execute(s -> runs.findStaleInFlight(org, seconds));
                            return candidates.stream()
                                    .filter(id -> Boolean.TRUE.equals(tx.execute(s -> failIfOrphaned(org, id))))
                                    .toList();
                        })
                        .stream())
                .toList();
    }

    private boolean failIfOrphaned(UUID org, UUID runId) {
        ReconciliationRun run = runs.lockByIdAndOrganizationId(runId, org).orElse(null);
        if (run == null
                || run.getStatus().isTerminal()
                || jobs.hasLiveJob(org, ReconcileJobs.TYPE, ReconcileJobs.RUN_ID, runId)) {
            return false;
        }
        log.error(
                "Run {} of organization {} is {} with no live job: failing it as orphaned",
                runId,
                org,
                run.getStatus());
        lifecycle.fail(org, runId, ORPHANED);
        orphaned.increment();
        return true;
    }
}
