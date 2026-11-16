package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M6.11: starting the "same run" twice returns the existing run (ADR 0006): with no header, the
 * same organization, period, sources and currency; with a header, the same key. Races are settled
 * by the database: two identical requests at once make one run and one job.
 */
class RunCreationIT extends AbstractRunApiIT {

    static final String OCTOBER = body("2026-10-01", "2026-10-31", "AED");

    @Test
    void aNewRequestIsAcceptedWithItsRunAndJobCommittedTogether() throws Exception {
        var f = new ReconFixture(jdbc);

        MockHttpServletResponse res = requestRun(f, OCTOBER, null);

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        Map<String, Object> run = json(res);
        UUID id = UUID.fromString(run.get("id").toString());
        assertThat(res.getHeader("Location")).isEqualTo("/api/v1/reconciliation-runs/" + id);
        assertThat(res.getHeader("Retry-After")).isEqualTo("2");
        assertThat(run)
                .containsEntry("status", "PENDING")
                .containsEntry("currency", "AED")
                .containsEntry("leftSource", "INTERNAL_LEDGER")
                .containsEntry("rightSource", "GATEWAY")
                .containsEntry("summary", null)
                .containsEntry("progress", Collections.singletonMap("lastCompletedDate", null))
                .containsEntry("triggeredBy", f.user.toString());
        assertThat(jdbc.queryForMap(
                        "SELECT idempotency_key, request_fingerprint FROM reconciliation_run WHERE id = ?", id))
                .satisfies(r -> assertThat((String) r.get("idempotency_key"))
                        .isEqualTo("auto:" + r.get("request_fingerprint")));
        assertThat(count(
                        "SELECT count(*) FROM job WHERE organization_id = ? AND type = 'RECONCILE'"
                                + " AND payload->>'runId' = ?",
                        f.org,
                        id.toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "SELECT actor_id, payload->>'idempotencyKeyType' AS kind, payload->>'currency' AS currency"
                                + " FROM audit_event WHERE entity_id = ? AND action = 'RUN_REQUESTED'",
                        id))
                .containsEntry("actor_id", f.user)
                .containsEntry("kind", "auto")
                .containsEntry("currency", "AED");
    }

    @Test
    void theSameRequestWithoutAHeaderReturnsTheExistingRun() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID first = id(requestRun(f, OCTOBER, null));

        MockHttpServletResponse again = requestRun(f, body("2026-10-01", "2026-10-31", "aed"), null);

        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(id(again)).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", f.org))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", f.org))
                .isEqualTo(1);
    }

    @Test
    void theSameKeyAndBodyReturnTheRunInWhateverStateItIs() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID first = id(requestRun(f, OCTOBER, "key-1"));
        jobRunner.runAllQueued(List.of(f.org));

        MockHttpServletResponse again = requestRun(f, OCTOBER, "key-1");

        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again)).containsEntry("id", first.toString()).containsEntry("status", "COMPLETED");
        assertThat(again.getHeader("Retry-After")).isNull(); // terminal: nothing to poll for
    }

    @Test
    void aReusedKeyWithADifferentBodyIs422() throws Exception {
        var f = new ReconFixture(jdbc);
        requestRun(f, OCTOBER, "key-1");

        MockHttpServletResponse res = requestRun(f, body("2026-10-01", "2026-10-30", "AED"), "key-1");

        assertThat(res.getStatus()).isEqualTo(422);
        assertThat(json(res)).containsEntry("code", "idempotency-key-reused");
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", f.org))
                .isEqualTo(1);
    }

    @Test
    void aFreshKeyRerunsAPeriodOnceTheFirstRunHasFinished() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID first = id(requestRun(f, OCTOBER, null));

        MockHttpServletResponse whileActive = requestRun(f, OCTOBER, "rerun-1");
        assertThat(whileActive.getStatus()).isEqualTo(409);
        assertThat(json(whileActive))
                .containsEntry("code", "run-in-progress")
                .containsEntry("existingRunId", first.toString());

        jobRunner.runAllQueued(List.of(f.org));
        MockHttpServletResponse afterwards = requestRun(f, OCTOBER, "rerun-1");
        assertThat(afterwards.getStatus()).isEqualTo(202);
        assertThat(id(afterwards)).isNotEqualTo(first);
    }

    @Test
    void noRunStartsWhileAnImportIsInProgress() throws Exception {
        var f = new ReconFixture(jdbc);
        f.batch("GATEWAY", "RECEIVED");

        MockHttpServletResponse res = requestRun(f, OCTOBER, null);

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json(res)).containsEntry("code", "import-in-progress");
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", f.org))
                .isZero();
    }

    @ParameterizedTest(name = "with key: {0}")
    @ValueSource(booleans = {false, true})
    void twoIdenticalRequestsAtOnceMakeOneRunAndOneJob(boolean withKey) throws Exception {
        var f = new ReconFixture(jdbc);
        @Nullable String key = withKey ? "same-" + UUID.randomUUID() : null;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return requestRun(f, OCTOBER, key);
                }));
            }
            go.countDown();
            MockHttpServletResponse a = calls.get(0).get(30, TimeUnit.SECONDS);
            MockHttpServletResponse b = calls.get(1).get(30, TimeUnit.SECONDS);

            assertThat(List.of(a.getStatus(), b.getStatus())).containsExactlyInAnyOrder(202, 200);
            assertThat(id(a)).isEqualTo(id(b));
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", f.org))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", f.org))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'RUN_REQUESTED'",
                        f.org))
                .isEqualTo(1);
    }

    private UUID id(MockHttpServletResponse res) throws Exception {
        return UUID.fromString(json(res).get("id").toString());
    }
}
