package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M6.15: {@code GET /reconciliation-runs/{id}} shows status, progress and, once COMPLETED, the
 * summary: counts of cases (not records), money as strings at the stored scale, next to the run's
 * currency.
 */
class RunReadIT extends AbstractRunApiIT {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void aCompletedRunShowsItsSummary() throws Exception {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-101", "1000.00", "AED", DAY);
        f.gateway("PAY-101", "1000.00", "AED", DAY);
        f.ledger("PAY-102", "500.00", "AED", DAY);
        f.ledger("PAY-103", "750.00", "AED", DAY);
        f.gateway("PAY-103", "700.00", "AED", DAY);
        f.gateway("PAY-104", "200.00", "AED", DAY);
        UUID run = runToCompletion(f, "2026-10-01", "2026-10-01", "AED");

        MockHttpServletResponse res = getAs(f, RUNS + "/" + run);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Retry-After")).isNull();
        Map<String, Object> body = json(res);
        assertThat(body)
                .containsEntry("status", "COMPLETED")
                .containsEntry("currency", "AED")
                .containsEntry("progress", Map.of("lastCompletedDate", "2026-10-01"))
                .containsEntry("failureReason", null);
        assertThat(body.get("startedAt")).isNotNull();
        assertThat(body.get("completedAt")).isNotNull();
        assertThat(body.get("summary"))
                .isEqualTo(Map.of(
                        "matched", Map.of("count", 1, "amount", "1000.0000"),
                        "amountMismatch", Map.of("count", 1, "netDifference", "-50.0000"),
                        "unmatchedLeft", Map.of("count", 1, "amount", "500.0000"),
                        "unmatchedRight", Map.of("count", 1, "amount", "200.0000"),
                        "duplicates", Map.of("count", 0),
                        "currencyMismatches", Map.of("count", 0),
                        "excluded", Map.of("count", 0)));
    }

    @Test
    void aPendingRunHasNoSummaryAndAsksToBePolled() throws Exception {
        var f = new ReconFixture(jdbc);
        MockHttpServletResponse created = requestRun(f, body("2026-10-01", "2026-10-31", "AED"), null);
        String id = json(created).get("id").toString();

        MockHttpServletResponse res = getAs(f, RUNS + "/" + id);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Retry-After")).isEqualTo("2");
        assertThat(json(res)).containsEntry("status", "PENDING").containsEntry("summary", null);
    }

    @Test
    void anUnknownRunIs404() throws Exception {
        var f = new ReconFixture(jdbc);

        MockHttpServletResponse res = getAs(f, RUNS + "/" + UUID.randomUUID());

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(json(res)).containsEntry("code", "not-found");
    }
}
