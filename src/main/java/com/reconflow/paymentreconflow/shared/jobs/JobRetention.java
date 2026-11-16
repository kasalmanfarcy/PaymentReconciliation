package com.reconflow.paymentreconflow.shared.jobs;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes finished jobs (M9.2): DONE after {@code reconflow.jobs.retention-done} (30 days), FAILED
 * after {@code retention-failed} (180 days, because a failed job's {@code last_error} is what an
 * operator diagnoses). Without it the table grows forever; the partial indexes keep claims fast
 * regardless, but every other query and every vacuum pays. Nothing is lost: a job's business outcome
 * lives in its batch, run or export and in {@code audit_event}. A background export's status
 * ({@code GET /reports/exports/{id}}) is a job, so it answers 404 once its job is deleted.
 *
 * <p>Daily at 03:30 UTC on worker nodes (scheduling is enabled only there). Several workers running
 * it at once is harmless: they delete disjoint or already-deleted rows.
 */
@Component
public class JobRetention {

    static final int BATCH = 10_000;

    private static final Logger log = LoggerFactory.getLogger(JobRetention.class);

    private final JobStore store;
    private final JobProperties props;

    JobRetention(JobStore store, JobProperties props) {
        this.store = store;
        this.props = props;
    }

    @Scheduled(cron = "${reconflow.jobs.retention-cron:0 30 3 * * *}", zone = "UTC")
    void daily() {
        purge();
    }

    /** Returns how many jobs were deleted. */
    public int purge() {
        int deleted = purge("DONE", props.retentionDone()) + purge("FAILED", props.retentionFailed());
        if (deleted > 0) {
            log.info("Job retention deleted {} finished job(s)", deleted);
        }
        return deleted;
    }

    private int purge(String status, Duration olderThan) {
        int total = 0;
        int n;
        do {
            n = store.deleteFinishedBefore(status, olderThan, BATCH);
            total += n;
        } while (n == BATCH);
        return total;
    }
}
