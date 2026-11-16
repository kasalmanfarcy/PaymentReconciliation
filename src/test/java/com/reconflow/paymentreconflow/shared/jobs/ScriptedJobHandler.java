package com.reconflow.paymentreconflow.shared.jobs;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A test-only handler whose behaviour comes from the job's payload ({@code behavior}), so queue
 * tests need no mutable shared state and no extra Spring context. It claims the {@code TEST} type,
 * which only the test database allows (db/test-callbacks): until M8 it borrowed {@code EXPORT},
 * which then had no production handler; M8's ExportJobHandler owns that type now, and a type with
 * two handlers is refused at startup.
 */
@Component
class ScriptedJobHandler implements JobHandler {

    static final String TYPE = "TEST";

    /** job id -> reason passed to onPermanentFailure. */
    static final Map<UUID, String> permanentFailures = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    ScriptedJobHandler(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(Job job, JobContext context) {
        switch (String.valueOf(job.payload().get("behavior"))) {
            case "succeed" -> {}
            // A "chunk": a business write and the fence in one transaction. If the fence fails, the
            // write must roll back with it.
            case "chunk" ->
                tx.executeWithoutResult(s -> {
                    jdbc.update(
                            "UPDATE organization SET name = 'written by " + job.workerId() + "' WHERE id = ?",
                            job.organizationId());
                    context.fence();
                    context.complete();
                });
            // A handler that checks the heartbeat's verdict before building its chunk (M9.1).
            case "guarded" -> {
                context.ensureLeaseHeld();
                jdbc.update(
                        "UPDATE organization SET name = 'guarded write by " + job.workerId() + "' WHERE id = ?",
                        job.organizationId());
            }
            case "transient" -> throw new TransientJobFailure("storage 503", new RuntimeException("503"));
            case "permanent" -> throw new PermanentJobFailure("bad-file");
            case "bug" -> throw new NullPointerException("a bug");
            case "stop" -> {
                if (context.stopRequested()) {
                    throw new JobStoppedException();
                }
            }
            default -> throw new IllegalArgumentException("unknown behavior");
        }
    }

    @Override
    public void onPermanentFailure(Job job, String reason) {
        permanentFailures.put(job.id(), reason);
    }
}
