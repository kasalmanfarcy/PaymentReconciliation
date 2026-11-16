package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M9.2: the queue's edge cases under concurrency, not just its happy path (phase-M9 §M9.2).
 *
 * <p>Fairness decision, tested as decided: the per-organization cap is <strong>soft</strong> (ADR
 * 0004, architecture.md §6). {@code SKIP LOCKED} locks the candidate row, not the "count of RUNNING
 * jobs" predicate, so claimers racing at the same instant can each see one job running and each
 * claim. The overshoot is bounded by the number of simultaneous claimers, and what fairness is
 * for (a small tenant isn't starved by a big one) holds.
 */
class JobQueueHardeningIT extends AbstractIntegrationTest {

    @Autowired
    JobQueue queue;

    @Autowired
    JobRunner runner;

    @Autowired
    JobRetention retention;

    @Autowired
    TransactionTemplate tx;

    @Test
    void eightWorkersDrainingTwoHundredJobsNeverClaimOneTwice() throws Exception {
        List<UUID> orgs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID org = data().newOrganization();
            orgs.add(org);
            enqueue(org, "succeed");
            enqueue(org, "succeed");
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<UUID>>> results = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                String worker = "w" + w;
                results.add(pool.submit(() -> {
                    start.await();
                    List<UUID> mine = new ArrayList<>();
                    Optional<Job> job;
                    while ((job = runner.claim(runner.workerId(worker), orgs)).isPresent()) {
                        mine.add(job.get().id());
                        runner.execute(job.get());
                    }
                    return mine;
                }));
            }
            start.countDown();
            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> r : results) {
                all.addAll(r.get(60, TimeUnit.SECONDS));
            }

            assertThat(all).hasSize(200).doesNotHaveDuplicates();
            assertThat(jdbc.queryForList(
                            "SELECT DISTINCT status || ':' || attempts FROM job WHERE organization_id = ANY(?)",
                            String.class,
                            (Object) orgs.toArray(UUID[]::new)))
                    .as("every job DONE on its first and only claim")
                    .containsExactly("DONE:1");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aBigTenantCannotStarveASmallOneAndItsOvershootIsBoundedByTheClaimers() throws Exception {
        UUID big = data().newOrganization();
        UUID small = data().newOrganization();
        List<UUID> bigJobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            bigJobs.add(enqueue(big, "slow"));
        }
        UUID smallJob = enqueue(small, "slow");
        List<UUID> orgs = List.of(big, small);

        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger maxBigRunning = new AtomicInteger();
        Map<UUID, Long> finishedAt = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            pool.submit(
                    () -> { // samples how many of the big tenant's jobs run at once
                        while (!done.get()) {
                            Integer n = jdbc.queryForObject(
                                    "SELECT count(*) FROM job WHERE organization_id = ? AND status = 'RUNNING'",
                                    Integer.class,
                                    big);
                            maxBigRunning.accumulateAndGet(n == null ? 0 : n, Math::max);
                            Thread.sleep(5);
                        }
                        return null;
                    });
            List<Future<?>> workers = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                String worker = "fair-" + w;
                workers.add(pool.submit(() -> {
                    Optional<Job> job;
                    while ((job = runner.claim(runner.workerId(worker), orgs)).isPresent()) {
                        runner.execute(job.get());
                        finishedAt.put(job.get().id(), System.nanoTime());
                    }
                    return null;
                }));
            }
            for (Future<?> f : workers) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            done.set(true);
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        long lastBig = bigJobs.stream().mapToLong(finishedAt::get).max().orElseThrow();
        assertThat(finishedAt.get(smallJob))
                .as("the small tenant's job ran before the big tenant's queue drained")
                .isLessThan(lastBig);
        // Soft cap: 2, plus at most one extra per other claimer racing at the same instant.
        assertThat(maxBigRunning.get()).isBetween(1, 2 + (4 - 1));
        assertThat(Collections.frequency(
                        jdbc.queryForList("SELECT status FROM job WHERE organization_id = ?", String.class, big),
                        "DONE"))
                .isEqualTo(30);
    }

    @Test
    void theClaimUsesThePartialQueuedIndexNotAScanOfFinishedJobs() {
        UUID org = data().newOrganization();
        // 100,000 finished jobs and 50 queued ones: the claim must not read the finished ones.
        jdbc.update("""
                INSERT INTO job (id, organization_id, type, payload, status, attempts, created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'TEST', '{"behavior":"succeed"}', 'DONE', 1,
                       now() - make_interval(secs => g), now() - make_interval(secs => g)
                  FROM generate_series(1, 100000) AS g""", org);
        for (int i = 0; i < 50; i++) {
            enqueue(org, "succeed");
        }
        jdbc.execute("ANALYZE job");

        String plan = String.join(
                "\n",
                jdbc.query(
                        c -> {
                            PreparedStatement ps = c.prepareStatement("EXPLAIN " + JobStore.CLAIM.formatted(""));
                            ps.setString(1, "explain-only");
                            ps.setDouble(2, 120);
                            ps.setInt(3, 2);
                            return ps;
                        },
                        (rs, n) -> rs.getString(1)));

        assertThat(plan).contains("job_claim_idx").doesNotContain("Seq Scan on job");
        jdbc.update("DELETE FROM job WHERE organization_id = ?", org); // keep the shared table small
    }

    @Test
    void retentionDeletesOldFinishedJobsAndKeepsEverythingElse() {
        UUID org = data().newOrganization();
        UUID oldDone = insert(org, "DONE", "31 days");
        UUID recentDone = insert(org, "DONE", "29 days");
        UUID oldFailed = insert(org, "FAILED", "181 days");
        UUID recentFailed = insert(org, "FAILED", "31 days");
        UUID queued = insert(org, "QUEUED", "400 days");

        retention.purge();

        Set<UUID> left =
                new HashSet<>(jdbc.queryForList("SELECT id FROM job WHERE organization_id = ?", UUID.class, org));
        assertThat(left).containsExactlyInAnyOrder(recentDone, recentFailed, queued);
        assertThat(left).doesNotContain(oldDone, oldFailed);
    }

    private UUID insert(UUID org, String status, String age) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO job (id, organization_id, type, payload, status, updated_at)"
                        + " VALUES (?, ?, 'TEST', '{}', ?, now() - ?::interval)",
                id,
                org,
                status,
                age);
        return id;
    }

    private UUID enqueue(UUID org, String behavior) {
        return tx.execute(s -> queue.enqueue(org, ScriptedJobHandler.TYPE, Map.of("behavior", behavior)));
    }
}
