package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M6.10/M6.12: a run that fails permanently on day N is FAILED with a user-safe reason and its
 * checkpoint at N−1. Retrying it resumes after the checkpoint, so the days already committed are
 * never redone, and the result equals a clean run's: same matches (no duplicate rows), same
 * observed cases, same summary.
 */
class RunRetryIT extends AbstractRunApiIT {

    static final LocalDate START = CheckpointResumeIT.START;
    static final LocalDate END = CheckpointResumeIT.END;

    @Autowired
    RunDriver driver;

    @Autowired
    ArmableRunHook hook;

    @Test
    void aRunFailedOnDay6RetriesToTheCleanResult() throws Exception {
        var f = new ReconFixture(jdbc);
        CheckpointResumeIT.tenDaysOfEverything(f);
        UUID run = driver.queueRun(f, START, END, "AED");
        hook.failOnceOn(run, START.plusDays(5));

        driver.runQueue(f.org);

        assertThat(driver.run(run))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "internal-error")
                .containsEntry("last_completed_date", java.sql.Date.valueOf(START.plusDays(4)));
        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT payload->>'reason' AS reason, payload->>'lastCompletedDate' AS checkpoint, request_id"
                        + " FROM audit_event WHERE entity_id = ? AND action = 'RUN_FAILED'",
                run);
        assertThat(failed).containsEntry("reason", "internal-error").containsEntry("checkpoint", "2026-10-05");
        assertThat((String) failed.get("request_id")).startsWith("job:");
        int matchesBefore = driver.matchedPairs(run).size();

        MockHttpServletResponse res = retry(f, run, "{\"reason\":\"fixed the bug\"}");

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        assertThat(json(res))
                .containsEntry("status", "PENDING")
                .containsEntry("failureReason", null)
                .containsEntry("progress", Map.of("lastCompletedDate", "2026-10-05"));
        assertThat(res.getHeader("Location")).isEqualTo("/api/v1/reconciliation-runs/" + run);
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ? AND status = 'QUEUED'", f.org))
                .isEqualTo(1);

        driver.runQueue(f.org);

        assertThat(driver.status(run)).isEqualTo("COMPLETED");
        assertThat(driver.matchedPairs(run)).hasSizeGreaterThan(matchesBefore);
        UUID clean = driver.run(f, START, END, "AED");
        assertThat(driver.matchedPairs(run)).isEqualTo(driver.matchedPairs(clean));
        assertThat(driver.observedCases(run)).isEqualTo(driver.observedCases(clean));
        assertThat(driver.summary(run)).isEqualTo(driver.summary(clean));
        assertThat(count(
                        "SELECT count(*) FROM (SELECT ledger_record_id FROM reconciliation_match WHERE run_id = ?"
                                + " GROUP BY 1 HAVING count(*) > 1) d",
                        run))
                .isZero();
        assertThat(jdbc.queryForList(
                        "SELECT action FROM audit_event WHERE entity_id = ? ORDER BY created_at, id",
                        String.class,
                        run))
                .containsSubsequence(
                        "RUN_STARTED", "RUN_FAILED", "RUN_RETRY_REQUESTED", "RUN_STARTED", "RUN_COMPLETED");
    }

    @Test
    void onlyAFailedRunCanBeRetried() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID run = driver.run(f, START, START, "AED");

        MockHttpServletResponse res = retry(f, run, "{\"reason\":\"again\"}");

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json(res)).containsEntry("code", "invalid-transition").containsEntry("currentStatus", "COMPLETED");
    }

    @Test
    void aRetryNeedsAReason() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID run = driver.queueRun(f, START, START, "AED");

        MockHttpServletResponse res = retry(f, run, "{\"reason\":\" \"}");

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json(res)).containsEntry("code", "validation-failed");
    }

    @Test
    void anUnknownRunIs404() throws Exception {
        var f = new ReconFixture(jdbc);

        MockHttpServletResponse res = retry(f, UUID.randomUUID(), "{\"reason\":\"x\"}");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(json(res)).containsEntry("code", "not-found");
    }

    @Test
    void aRetryWhileAnotherRunOfTheScopeIsActiveIs409() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID failed = driver.queueRun(f, START, START, "AED");
        hook.failOnceOn(failed, START);
        driver.runQueue(f.org);
        UUID active = driver.queueRun(f, START, START, "AED"); // a new key for the same scope

        MockHttpServletResponse res = retry(f, failed, "{\"reason\":\"x\"}");

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json(res))
                .containsEntry("code", "run-in-progress")
                .containsEntry("existingRunId", active.toString());
        assertThat(driver.status(failed)).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ? AND status = 'QUEUED'", f.org))
                .isEqualTo(1);
    }

    private MockHttpServletResponse retry(ReconFixture f, UUID run, String body) throws Exception {
        return mockMvc.perform(post(RUNS + "/" + run + "/retry")
                        .with(as(f.user))
                        .with(org(f.org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }
}
