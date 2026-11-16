package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/** M9.3: the in-process retry wraps the transaction, retries a real deadlock, and nothing permanent. */
class TransientRetryIT extends AbstractIntegrationTest {

    @Autowired
    TransientRetry retry;

    @Autowired
    TransactionTemplate tx;

    @Test
    void refusesToRunInsideATransactionBecauseAnAbortedTransactionCantBeRetried() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> retry.run("inside", () -> {})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrap the transaction");
    }

    @Test
    void aRealDeadlockVictimIsRetriedAsANewTransactionAndBothSucceed() throws Exception {
        // Two organizations' rows, locked in opposite orders by two transactions that meet in the
        // middle: PostgreSQL's deadlock detector aborts one of them (40P01).
        UUID a = data().newOrganization();
        UUID b = data().newOrganization();
        CyclicBarrier bothHoldTheirFirstLock = new CyclicBarrier(2);
        AtomicInteger executions = new AtomicInteger();

        CompletableFuture<Void> one = CompletableFuture.runAsync(() -> retry.run(
                "test.deadlock",
                () -> tx.executeWithoutResult(s -> {
                    boolean first = executions.incrementAndGet() <= 2;
                    touch(a, "one");
                    if (first) {
                        await(bothHoldTheirFirstLock);
                    }
                    touch(b, "one");
                })));
        CompletableFuture<Void> two = CompletableFuture.runAsync(() -> retry.run(
                "test.deadlock",
                () -> tx.executeWithoutResult(s -> {
                    boolean first = executions.incrementAndGet() <= 2;
                    touch(b, "two");
                    if (first) {
                        await(bothHoldTheirFirstLock);
                    }
                    touch(a, "two");
                })));

        CompletableFuture.allOf(one, two).get(60, TimeUnit.SECONDS);

        assertThat(executions.get())
                .as("two attempts, plus one retry of the deadlock victim")
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM organization WHERE id IN (?, ?) AND name LIKE 'touched by %'",
                        Integer.class, a, b))
                .isEqualTo(2);
    }

    @Test
    void aPermanentFailureIsThrownAtOnceUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("test.permanent", () -> {
                    calls.incrementAndGet();
                    throw new DataIntegrityViolationException("duplicate");
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    private void touch(UUID org, String who) {
        jdbc.update("UPDATE organization SET name = ? WHERE id = ?", "touched by " + who, org);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
