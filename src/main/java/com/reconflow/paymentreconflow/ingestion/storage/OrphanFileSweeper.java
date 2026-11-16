package com.reconflow.paymentreconflow.ingestion.storage;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.shared.storage.FileStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes uploaded files that no batch references (M5.8). They are the deliberate leftover of the
 * upload order: the file is stored <em>before</em> the batch row commits, because an orphaned file
 * is invisible and cheap while a batch pointing at a missing file is a user-visible bug (phase-M5
 * §2.3). A loser of a duplicate-upload race also leaves one if its clean-up delete fails.
 *
 * <p>Only objects older than {@link #MIN_AGE} are candidates, so the sweeper never races an upload
 * whose transaction hasn't committed yet. Works one organization at a time (the first key segment)
 * with that organization bound, so row-level security sees its batches. Daily, worker nodes only
 * (scheduling is enabled only there).
 */
@Component
public class OrphanFileSweeper {

    static final Duration MIN_AGE = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(OrphanFileSweeper.class);

    private final FileStorage storage;
    private final ImportBatchRepository batches;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final Clock clock;

    OrphanFileSweeper(
            FileStorage storage,
            ImportBatchRepository batches,
            TenantScope tenantScope,
            TransactionTemplate tx,
            Clock clock) {
        this.storage = storage;
        this.batches = batches;
        this.tenantScope = tenantScope;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(cron = "${reconflow.storage.orphan-sweep-cron:0 45 2 * * *}", zone = "UTC")
    void sweepDaily() {
        int deleted = sweepOlderThan(clock.instant().minus(MIN_AGE));
        log.info("Orphan sweep deleted {} unreferenced upload(s)", deleted);
    }

    /** Deletes unreferenced objects last modified before {@code cutoff}; returns how many. */
    public int sweepOlderThan(Instant cutoff) {
        int deleted = 0;
        for (String prefix : storage.topLevelPrefixes()) {
            UUID organizationId;
            try {
                organizationId = UUID.fromString(prefix);
            } catch (IllegalArgumentException e) {
                continue; // not ours; the bucket is dedicated, but don't touch what we didn't write
            }
            deleted += sweepOrganization(organizationId, cutoff);
        }
        return deleted;
    }

    private int sweepOrganization(UUID organizationId, Instant cutoff) {
        List<String> candidates = new ArrayList<>();
        storage.forEach(StorageKeys.importsPrefix(organizationId), o -> {
            if (o.lastModified().isBefore(cutoff)) {
                candidates.add(o.key());
            }
        });
        int deleted = 0;
        for (String key : candidates) {
            boolean referenced = tenantScope.call(
                    organizationId,
                    () -> Boolean.TRUE.equals(
                            tx.execute(s -> batches.existsByOrganizationIdAndStorageKey(organizationId, key))));
            if (!referenced) {
                storage.delete(key);
                deleted++;
            }
        }
        return deleted;
    }
}
