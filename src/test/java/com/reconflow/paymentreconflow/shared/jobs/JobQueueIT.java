package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue's guarantees (phase-M5 "Job queue", Done when). Every test works only on jobs of its
 * own organizations: the database is shared and never truncated.
 */
class JobQueueIT extends AbstractIntegrationTest {

    @Autowired
    JobQueue queue;

    @Autowired
    JobRunner runner;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void resume() {
        runner.clearStop();
    }

    @Test
    void enqueueingOutsideATransactionIsRefused() {
        UUID org = data().newOrganization();
        assertThatThrownBy(() -> queue.enqueue(org, ScriptedJobHandler.TYPE, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void twoConcurrentClaimersNeverGetTheSameJob() throws Exception {
        List<UUID> orgs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID org = data().newOrganization();
            orgs.add(org);
            enqueue(org, "succeed");
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<UUID>>> results = new ArrayList<>();
            for (String worker : List.of("A", "B")) {
                results.add(pool.submit(() -> {
                    start.await();
                    List<UUID> mine = new ArrayList<>();
                    Optional<Job> job;
                    while ((job = runner.claim(runner.workerId(worker), orgs)).isPresent()) {
                        mine.add(job.get().id());
                    }
                    return mine;
                }));
            }
            start.countDown();
            List<UUID> a = results.get(0).get();
            List<UUID> b = results.get(1).get();

            assertThat(a).doesNotContainAnyElementsOf(b);
            assertThat(a.size() + b.size()).isEqualTo(12);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anExpiredLeaseIsRequeuedAndTheJobCompletesOnTheNextClaim() {
        UUID org = data().newOrganization();
        UUID id = enqueue(org, "succeed");
        Job crashed = runner.claim(runner.workerId("dies"), List.of(org)).orElseThrow();
        assertThat(status(id)).isEqualTo("RUNNING");

        expireLease(id);
        runner.sweepExpiredLeases();
        assertThat(status(id)).isEqualTo("QUEUED");

        assertThat(runner.runAllQueued(List.of(org))).isEqualTo(1);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(crashed.attempts()).isEqualTo(1);
    }

    @Test
    void aZombieWhoseLeaseWasTakenCommitsNothing() {
        UUID org = data().newOrganization();
        String originalName = jdbc.queryForObject("SELECT name FROM organization WHERE id = ?", String.class, org);
        UUID id = enqueue(org, "chunk");
        Job zombie = runner.claim(runner.workerId("zombie"), List.of(org)).orElseThrow();
        // The zombie pauses past its lease; the sweeper hands the job to someone else.
        expireLease(id);
        runner.sweepExpiredLeases();
        Job successor = runner.claim(runner.workerId("successor"), List.of(org)).orElseThrow();

        runner.execute(zombie); // wakes up and tries to commit a chunk

        assertThat(jdbc.queryForObject("SELECT name FROM organization WHERE id = ?", String.class, org))
                .isEqualTo(originalName);
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT locked_by FROM job WHERE id = ?", String.class, id))
                .isEqualTo(successor.workerId());

        runner.execute(successor);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT name FROM organization WHERE id = ?", String.class, org))
                .isEqualTo("written by " + successor.workerId());
    }

    @Test
    void aHeartbeatThatFindsTheLeaseGoneStopsTheHandlerBeforeItWritesAnything() {
        UUID org = data().newOrganization();
        String originalName = jdbc.queryForObject("SELECT name FROM organization WHERE id = ?", String.class, org);
        UUID id = enqueue(org, "guarded");
        Job paused = runner.claim(runner.workerId("paused"), List.of(org)).orElseThrow();
        expireLease(id);
        runner.sweepExpiredLeases();
        runner.claim(runner.workerId("successor"), List.of(org)).orElseThrow();

        // The paused worker's heartbeat thread runs first and finds the lease gone.
        assertThat(runner.heartbeat(paused.id(), paused.workerId())).isFalse();
        runner.execute(paused);

        assertThat(jdbc.queryForObject("SELECT name FROM organization WHERE id = ?", String.class, org))
                .isEqualTo(originalName);
        assertThat(status(id)).isEqualTo("RUNNING");
    }

    @Test
    void theStartupSweepRecoversOnlyExpiredLeases() {
        UUID org = data().newOrganization();
        UUID dead = enqueue(org, "succeed");
        UUID alive = enqueue(org, "succeed");
        runner.claim(runner.workerId("crashed-predecessor"), List.of(org)).orElseThrow();
        runner.claim(runner.workerId("live-neighbour"), List.of(org)).orElseThrow();
        expireLease(dead);

        new JobWorker(runner, new JobWorkerProperties(true, 1)).sweepOnStartup();

        assertThat(status(dead)).isEqualTo("QUEUED");
        assertThat(status(alive))
                .as("another worker's valid lease is left alone")
                .isEqualTo("RUNNING");
    }

    @Test
    void transientFailuresBackOffWithIncreasingDelaysThenFail() {
        UUID org = data().newOrganization();
        UUID id = enqueue(org, "transient");

        runner.runAllQueued(List.of(org));
        Duration first = delay(id);
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(first).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(25)); // 2^1 × 10 s ± 20 %

        jdbc.update("UPDATE job SET run_after = now() WHERE id = ?", id); // skip the wait
        runner.runAllQueued(List.of(org));
        Duration second = delay(id);
        assertThat(second).isBetween(Duration.ofSeconds(31), Duration.ofSeconds(49)); // 2^2 × 10 s ± 20 %
        assertThat(attempts(id)).isEqualTo(2);
        assertThat(lastError(id)).contains("storage 503");

        // The last allowed attempt fails too: no more retries, the handler is told.
        jdbc.update("UPDATE job SET run_after = now(), attempts = max_attempts - 1 WHERE id = ?", id);
        runner.runAllQueued(List.of(org));
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(ScriptedJobHandler.permanentFailures).containsEntry(id, "retries-exhausted");
    }

    @Test
    void permanentFailuresAndBugsFailAtOnce() {
        UUID org = data().newOrganization();
        UUID permanent = enqueue(org, "permanent");
        UUID bug = enqueue(org, "bug");

        runner.runAllQueued(List.of(org));

        assertThat(status(permanent)).isEqualTo("FAILED");
        assertThat(attempts(permanent)).isEqualTo(1);
        assertThat(ScriptedJobHandler.permanentFailures).containsEntry(permanent, "bad-file");
        // A bug is permanent too, and its details stay in the job row, not in the user-facing reason.
        assertThat(status(bug)).isEqualTo("FAILED");
        assertThat(ScriptedJobHandler.permanentFailures).containsEntry(bug, "internal-error");
        assertThat(lastError(bug)).contains("NullPointerException");
    }

    @Test
    void aThirdJobOfAnOrganizationWithTwoRunningWaitsWhileAnotherOrganizationsRuns() {
        UUID busy = data().newOrganization();
        UUID other = data().newOrganization();
        enqueue(busy, "succeed");
        enqueue(busy, "succeed");
        UUID third = enqueue(busy, "succeed");
        UUID others = enqueue(other, "succeed");
        runner.claim(runner.workerId("w1"), List.of(busy)).orElseThrow();
        runner.claim(runner.workerId("w2"), List.of(busy)).orElseThrow();

        assertThat(runner.claim(runner.workerId("w3"), List.of(busy, other)))
                .map(Job::id)
                .contains(others);
        assertThat(runner.claim(runner.workerId("w4"), List.of(busy))).isEmpty();
        assertThat(status(third)).isEqualTo("QUEUED");
    }

    @Test
    void anExpiredLeaseWithNoAttemptsLeftIsFailedByTheSweeper() {
        UUID org = data().newOrganization();
        UUID id = enqueue(org, "succeed");
        runner.claim(runner.workerId("dies"), List.of(org)).orElseThrow();
        jdbc.update(
                "UPDATE job SET attempts = max_attempts, lease_until = now() - interval '1 second' WHERE id = ?", id);

        runner.sweepExpiredLeases();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(ScriptedJobHandler.permanentFailures).containsEntry(id, "retries-exhausted");
    }

    @Test
    void onShutdownARunningJobIsHandedBackWithoutSpendingAnAttempt() {
        UUID org = data().newOrganization();
        UUID id = enqueue(org, "stop");
        Job running = runner.claim(runner.workerId("w"), List.of(org)).orElseThrow();
        runner.requestStop(); // SIGTERM arrives while the job is between chunks

        runner.execute(running);

        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(attempts(id)).isZero();
        runner.clearStop();
        runner.runAllQueued(List.of(org));
        assertThat(status(id)).isEqualTo("DONE");
    }

    private UUID enqueue(UUID org, String behavior) {
        return tx.execute(s -> queue.enqueue(org, ScriptedJobHandler.TYPE, Map.of("behavior", behavior)));
    }

    private void expireLease(UUID id) {
        jdbc.update("UPDATE job SET lease_until = now() - interval '1 second' WHERE id = ?", id);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, id);
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM job WHERE id = ?", Integer.class, id);
    }

    private String lastError(UUID id) {
        return jdbc.queryForObject("SELECT last_error FROM job WHERE id = ?", String.class, id);
    }

    private Duration delay(UUID id) {
        Double seconds = jdbc.queryForObject(
                "SELECT extract(epoch FROM run_after - now()) FROM job WHERE id = ?", Double.class, id);
        return Duration.ofMillis(Math.round(seconds * 1000));
    }
}
