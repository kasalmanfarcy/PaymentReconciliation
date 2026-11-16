package com.reconflow.paymentreconflow.shared.jobs;

import com.reconflow.paymentreconflow.shared.id.Ids;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enqueues work. {@link Propagation#MANDATORY}: a job is always inserted in the same transaction as
 * the business row it is about (the batch, the run) and its audit event, so a job can't exist
 * without its batch or a batch without its job. That is <em>transactional enqueueing</em>, and the
 * main reason a table beats a broker here: "commit, then publish" can lose the message in a crash
 * between the two (the problem the outbox pattern exists to solve).
 */
@Service
public class JobQueue {

    private final JobStore store;

    JobQueue(JobStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(UUID organizationId, String type, Map<String, ?> payload) {
        UUID id = Ids.newId();
        store.insert(id, organizationId, type, payload);
        return id;
    }

    /**
     * A job of this organization, for a status endpoint; empty for an unknown id or another
     * organization's job, which callers must answer identically (404, ADR 0008).
     */
    @Transactional(readOnly = true)
    public Optional<JobStatus> find(UUID organizationId, UUID jobId) {
        return store.find(organizationId, jobId);
    }

    /**
     * Does this business row (a batch, a run) still have a job that will process it? False means
     * it is orphaned: in flight with nothing to move it on (M9.1's consistency check).
     */
    @Transactional(readOnly = true)
    public boolean hasLiveJob(UUID organizationId, String type, String payloadKey, UUID subjectId) {
        return store.hasLiveJob(organizationId, type, payloadKey, subjectId);
    }
}
