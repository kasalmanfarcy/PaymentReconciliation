package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.ScriptedFaultInjector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M9.11: on shutdown a worker hands its job back at the next chunk boundary, without spending an
 * attempt, so another worker resumes it at once instead of after the 2-minute lease. Driven through
 * the real {@link JobWorker}: its poller claims, its pool thread runs the import, and its
 * {@code SmartLifecycle.stop()} is what the context calls on SIGTERM. (M5 tested only the runner;
 * a real SIGTERM to the packaged app is in docs/benchmarks.md §5.)
 */
class GracefulShutdownIT extends AbstractIntegrationTest {

    @Autowired
    JobRunner runner;

    @Autowired
    ScriptedFaultInjector faults;

    @AfterEach
    void clear() {
        faults.clear();
        runner.clearStop();
    }

    @Test
    void stoppingTheWorkerMidImportHandsTheJobBackAndAnotherWorkerFinishesIt() throws Exception {
        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        StringBuilder csv = new StringBuilder("reference,amount,currency,status,transaction_date\n");
        for (int i = 1; i <= 1000; i++) {
            csv.append("GS-")
                    .append(i)
                    .append(",")
                    .append(i)
                    .append(".00,AED,SUCCESS,2026-10-0")
                    .append(1 + i % 5)
                    .append('\n');
        }
        mockMvc.perform(multipart("/api/v1/imports")
                .file(new MockMultipartFile(
                        "file", "gs.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8)))
                .param("sourceType", "INTERNAL_LEDGER")
                .with(as(user))
                .with(org(org)));
        UUID job = jdbc.queryForObject("SELECT id FROM job WHERE organization_id = ?", UUID.class, org);

        // Hold the worker inside its third chunk until SIGTERM has "arrived".
        CountDownLatch inThirdChunk = new CountDownLatch(1);
        CountDownLatch sigterm = new CountDownLatch(1);
        faults.onHit(FaultPoints.IMPORT_AFTER_CHUNK_COMMIT, 3, () -> {
            inThirdChunk.countDown();
            await(sigterm);
        });

        // A real worker with one slot, narrowed to this organization (the test database is shared).
        JobWorker worker = new JobWorker(runner, new JobWorkerProperties(true, 1), List.of(org));
        worker.start();
        worker.poll();
        assertThat(inThirdChunk.await(30, TimeUnit.SECONDS)).isTrue();

        Thread stopper = Thread.ofPlatform().start(worker::stop); // what the context does on SIGTERM
        Thread.sleep(200); // stop() has asked handlers to stop and is waiting for the pool
        sigterm.countDown();
        stopper.join(Duration.ofSeconds(30));

        Map<String, Object> handedBack =
                jdbc.queryForMap("SELECT status, attempts, locked_by FROM job WHERE id = ?", job);
        assertThat(handedBack.get("status")).isEqualTo("QUEUED");
        assertThat(handedBack.get("attempts"))
                .as("a graceful stop spends no attempt")
                .isEqualTo(0);
        assertThat(handedBack.get("locked_by")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT last_committed_row FROM import_batch WHERE organization_id = ?", Integer.class, org))
                .as("stopped at a chunk boundary, its work kept")
                .isEqualTo(300);

        runner.clearStop(); // another worker, immediately: no lease to wait out
        runner.runAllQueued(List.of(org));

        assertThat(jdbc.queryForMap("SELECT status, valid_rows FROM import_batch WHERE organization_id = ?", org))
                .containsEntry("status", "COMPLETED")
                .containsEntry("valid_rows", 1000);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transaction_record WHERE organization_id = ?", Integer.class, org))
                .isEqualTo(1000);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
