package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.14: two people resolving the same case at once get exactly one success and one {@code 409},
 * and nothing is silently overwritten. There are two races (phase-M7 §2.4), so two proofs:
 *
 * <ul>
 *   <li><b>Slow</b>: the second client acts on a stale page. Only the explicit If-Match compare
 *       catches it (each request loads the case fresh, so {@code @Version} sees no conflict).</li>
 *   <li><b>Fast</b>: both requests pass the compare in the same instant. A test barrier holds both
 *       right after the compare, so the race is certain, not likely; then only {@code @Version}'s
 *       {@code UPDATE ... WHERE version = 0} can stop the second.</li>
 * </ul>
 *
 * Each request runs on its own thread with its own transaction (no test is ever
 * {@code @Transactional}), and every test asserts the <em>data</em>, not just the status codes:
 * "no silent overwrite" is a property of the database.
 */
class ExceptionConcurrencyIT extends AbstractExceptionApiIT {

    @Autowired
    ArmableExceptionWriteHook hook;

    @Test
    void theSecondResolveOfTheSameVersionGets409() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse first = resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null);
        MockHttpServletResponse second = resolve(f, id, f.admin(), "\"0\"", "DATA_ENTRY_ERROR", "typo");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(json(second)).containsEntry("code", "stale-version").containsEntry("currentVersion", 1);
        assertOneResolution(f, id, "GATEWAY_FEE");
    }

    @Test
    void twoResolvesThatBothPassTheVersionCheckStillProduceOneWinner() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        hook.holdAfterVersionCheck(id, new CyclicBarrier(2));
        try {
            List<MockHttpServletResponse> results = race(
                    () -> resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null),
                    () -> resolve(f, id, f.admin(), "\"0\"", "DATA_ENTRY_ERROR", "typo"));

            assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
            MockHttpServletResponse loser = results.stream()
                    .filter(r -> r.getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(json(loser)).containsEntry("code", "stale-version").containsEntry("currentVersion", 1);
            MockHttpServletResponse winner = results.stream()
                    .filter(r -> r.getStatus() == 200)
                    .findFirst()
                    .orElseThrow();
            @SuppressWarnings("unchecked")
            String winningCode = (String) ((Map<String, Object>) json(winner).get("resolution")).get("code");
            assertOneResolution(f, id, winningCode);
        } finally {
            hook.disarm(id);
        }
    }

    @Test
    void anAssignmentAndAResolveRacingProduceOneWinner() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        hook.holdAfterVersionCheck(id, new CyclicBarrier(2));
        try {
            List<MockHttpServletResponse> results = race(
                    () -> patchCase(f, id, f.admin(), "\"0\"", "{\"assignedTo\":\"" + f.admin() + "\"}"),
                    () -> resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null));

            assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
            assertThat(f.version(id)).isOne();
            // Exactly one of the two changes happened, with its own audit event and nothing else.
            assertThat(f.auditRows(id, "EXCEPTION_ASSIGNED") + f.auditRows(id, "EXCEPTION_RESOLVED"))
                    .isOne();
        } finally {
            hook.disarm(id);
        }
    }

    /** A smoke test without the barrier: the race is likely, not certain, so it can't prove much alone. */
    @RepeatedTest(20)
    void concurrentResolvesProduceExactlyOneSuccess() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        List<MockHttpServletResponse> results = race(
                () -> resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null),
                () -> resolve(f, id, f.admin(), "\"0\"", "TIMING_DIFFERENCE", null));

        assertThat(results).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        assertThat(f.historyRows(id)).isEqualTo(2); // created + one resolve
        assertThat(f.auditRows(id, "EXCEPTION_RESOLVED")).isOne();
    }

    private void assertOneResolution(ExceptionFixture f, UUID id, String code) {
        assertThat(f.caseRow(id)).containsEntry("status", "RESOLVED").containsEntry("resolution_code", code);
        assertThat(f.version(id)).isOne();
        assertThat(f.count(
                        "SELECT count(*) FROM exception_status_history WHERE exception_id = ? AND to_status = 'RESOLVED'",
                        id))
                .isOne();
        assertThat(f.auditRows(id, "EXCEPTION_RESOLVED")).isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'resolutionCode' FROM audit_event WHERE entity_id = ? AND action = 'EXCEPTION_RESOLVED'",
                        String.class,
                        id))
                .isEqualTo(code);
    }

    /** Both calls released together, each on its own thread (and so its own transaction). */
    @SafeVarargs
    private List<MockHttpServletResponse> race(Callable<MockHttpServletResponse>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<MockHttpServletResponse>> futures = java.util.Arrays.stream(calls)
                    .map(call -> pool.submit(() -> {
                        start.await();
                        return call.call();
                    }))
                    .toList();
            start.countDown();
            List<MockHttpServletResponse> results = new java.util.ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
