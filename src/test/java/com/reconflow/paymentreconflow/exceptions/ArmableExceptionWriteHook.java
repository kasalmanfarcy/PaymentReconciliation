package com.reconflow.paymentreconflow.exceptions;

import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionWriteHook;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Replaces the production no-op hook in every test context (a component, not a per-test override,
 * so no extra Spring context), like M6's ArmableRunHook. Disarmed unless a test arms it for its own
 * case id, so other tests never notice it.
 */
@Primary
@Component
class ArmableExceptionWriteHook implements ExceptionWriteHook {

    private final Map<UUID, CyclicBarrier> barriers = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> failBeforeCommit = new ConcurrentHashMap<>();

    /** Every write of this case waits here after passing the If-Match compare, until all parties arrive. */
    void holdAfterVersionCheck(UUID caseId, CyclicBarrier barrier) {
        barriers.put(caseId, barrier);
    }

    /** The next write of this case throws after its change, history and audit were flushed. */
    void failOnceBeforeCommit(UUID caseId) {
        failBeforeCommit.put(caseId, true);
    }

    void disarm(UUID caseId) {
        barriers.remove(caseId);
        failBeforeCommit.remove(caseId);
    }

    @Override
    public void afterVersionCheck(UUID caseId) {
        CyclicBarrier barrier = barriers.get(caseId);
        if (barrier == null) {
            return;
        }
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("the other request never reached the barrier", e);
        }
    }

    @Override
    public void beforeCommit(UUID caseId) {
        if (failBeforeCommit.remove(caseId) != null) {
            throw new IllegalStateException("injected failure before commit");
        }
    }
}
