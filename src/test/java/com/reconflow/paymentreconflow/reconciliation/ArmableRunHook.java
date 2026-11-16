package com.reconflow.paymentreconflow.reconciliation;

import com.reconflow.paymentreconflow.reconciliation.application.RunChunkHook;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Failure injection for M6.9 and M6.12, replacing the production no-op hook in every test context
 * (a component, not a per-test override, so no extra Spring context). Disarmed unless a test arms it
 * for its own run. Fires inside the day's transaction, after its matches and cases, before the
 * checkpoint: the worst moment, since all of that day's work must roll back.
 */
@Primary
@Component
class ArmableRunHook implements RunChunkHook {

    /** An Error, which the queue doesn't catch: the job stays RUNNING with its lease, like a killed JVM. */
    static final class SimulatedCrash extends Error {
        SimulatedCrash(LocalDate day) {
            super("simulated crash on " + day);
        }
    }

    private final Map<UUID, LocalDate> crashOn = new ConcurrentHashMap<>();
    private final Map<UUID, LocalDate> failOnceOn = new ConcurrentHashMap<>();

    void crashOn(UUID run, LocalDate day) {
        crashOn.put(run, day);
    }

    /** A RuntimeException, once: the queue treats it as a bug, a permanent failure (run FAILED). */
    void failOnceOn(UUID run, LocalDate day) {
        failOnceOn.put(run, day);
    }

    void disarm(UUID run) {
        crashOn.remove(run);
        failOnceOn.remove(run);
    }

    @Override
    public void beforeCheckpoint(UUID runId, LocalDate day) {
        if (day.equals(crashOn.get(runId))) {
            throw new SimulatedCrash(day);
        }
        if (failOnceOn.remove(runId, day)) {
            throw new IllegalStateException("injected failure on " + day);
        }
    }
}
