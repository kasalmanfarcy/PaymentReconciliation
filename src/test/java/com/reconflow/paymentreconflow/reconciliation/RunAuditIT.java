package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M6.13: "who started this run, when, and what did it find?" is answered by the audit trail, each
 * event written in the same transaction as the change it describes. User actions carry the user and
 * the HTTP request id; worker actions have no actor and carry {@code job:<jobId>}.
 */
class RunAuditIT extends AbstractRunApiIT {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void aCompletedRunHasExactlyOneRequestedStartedAndCompletedEvent() throws Exception {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-101", "1000.00", "AED", DAY);
        f.gateway("PAY-101", "1000.00", "AED", DAY);
        f.ledger("PAY-103", "750.00", "AED", DAY);
        f.gateway("PAY-103", "700.00", "AED", DAY);
        MockHttpServletResponse res = requestRun(f, body("2026-10-01", "2026-10-01", "AED"), "audit-1");
        UUID run = UUID.fromString(json(res).get("id").toString());
        jobRunner.runAllQueued(List.of(f.org));

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT action, actor_id, request_id, payload FROM audit_event WHERE entity_id = ?"
                        + " ORDER BY created_at, id",
                run);

        assertThat(events)
                .extracting(e -> e.get("action"))
                .containsExactly("RUN_REQUESTED", "RUN_STARTED", "RUN_COMPLETED");
        Map<String, Object> requested = events.get(0);
        assertThat(requested.get("actor_id")).isEqualTo(f.user);
        assertThat(requested.get("request_id")).isEqualTo(res.getHeader("X-Request-Id"));
        assertThat(requested.get("payload").toString())
                .contains("\"periodStart\": \"2026-10-01\"", "\"idempotencyKeyType\": \"client\"");
        UUID job = jdbc.queryForObject("SELECT id FROM job WHERE organization_id = ?", UUID.class, f.org);
        for (Map<String, Object> workerEvent : events.subList(1, 3)) {
            assertThat(workerEvent.get("actor_id")).isNull();
            assertThat(workerEvent.get("request_id")).isEqualTo("job:" + job);
        }
        assertThat(events.get(2).get("payload").toString())
                .contains(
                        "\"matchedCount\": 1",
                        "\"matchedAmount\": \"1000.0000\"",
                        "\"mismatchNetDifference\": \"-50.0000\"");
        // The case the run raised is audited by the exceptions module, as the system.
        assertThat(jdbc.queryForList(
                        "SELECT actor_id, request_id FROM audit_event WHERE organization_id = ? AND action = 'EXCEPTION_CREATED'",
                        f.org))
                .singleElement()
                .satisfies(
                        e -> assertThat(e).containsEntry("actor_id", null).containsEntry("request_id", "job:" + job));
    }

    @Test
    void aRepeatedRequestAuditsNothingNew() throws Exception {
        var f = new ReconFixture(jdbc);
        requestRun(f, body("2026-10-01", "2026-10-01", "AED"), null);
        requestRun(f, body("2026-10-01", "2026-10-01", "AED"), null);

        assertThat(count(
                        "SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'RUN_REQUESTED'",
                        f.org))
                .isEqualTo(1);
    }
}
