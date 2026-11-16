package com.reconflow.paymentreconflow.ingestion;

import com.reconflow.paymentreconflow.ingestion.application.ImportProcessingHook;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Failure injection for M5.16 and M5.25, replacing the production no-op hook in every test context
 * (a component, not a per-test override, so no extra Spring context). Disarmed unless a test arms it
 * for its own batch.
 *
 * <p>It throws {@link SimulatedCrash}, an {@link Error}: the queue handles exceptions (retries,
 * failures) but lets an Error escape, so the job is left RUNNING with its lease exactly as if the
 * JVM had been killed at that line.
 */
@Primary
@Component
class ArmableImportHook implements ImportProcessingHook {

    static final class SimulatedCrash extends Error {
        SimulatedCrash(String where) {
            super("simulated crash " + where);
        }
    }

    private final Map<UUID, Integer> crashAfterRow = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> crashBeforeFinish = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> failAfterRow = new ConcurrentHashMap<>();

    void crashAfterRow(UUID batch, int row) {
        crashAfterRow.put(batch, row);
    }

    void crashBeforeFinishCommit(UUID batch) {
        crashBeforeFinish.put(batch, true);
    }

    /** A RuntimeException instead: the queue treats it as a bug, a permanent failure. */
    void failAfterRow(UUID batch, int row) {
        failAfterRow.put(batch, row);
    }

    void disarm(UUID batch) {
        crashAfterRow.remove(batch);
        crashBeforeFinish.remove(batch);
        failAfterRow.remove(batch);
    }

    @Override
    public void afterRow(UUID batchId, int rowNumber) {
        if (Integer.valueOf(rowNumber).equals(crashAfterRow.get(batchId))) {
            throw new SimulatedCrash("after row " + rowNumber);
        }
        if (Integer.valueOf(rowNumber).equals(failAfterRow.get(batchId))) {
            throw new IllegalStateException("injected failure after row " + rowNumber);
        }
    }

    @Override
    public void beforeFinishCommit(UUID batchId) {
        if (crashBeforeFinish.containsKey(batchId)) {
            throw new SimulatedCrash("before the finishing commit");
        }
    }
}
