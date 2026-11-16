package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.12: every action on a case leaves exactly one audit event, in the same transaction as the
 * change; a write that fails after applying its change leaves neither.
 */
class ExceptionAuditIT extends AbstractExceptionApiIT {

    @Autowired
    ArmableExceptionWriteHook hook;

    @Test
    void everyActionIsInTheCasesTimelineInOrderWithItsActor() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        patchCase(f, id, f.investigator(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");
        patchCase(f, id, f.investigator(), "\"1\"", "{\"status\":\"IN_REVIEW\"}");
        addNote(f, id, f.investigator(), "Gateway support confirms a 50 AED fee.");
        resolve(f, id, f.investigator(), "\"2\"", "GATEWAY_FEE", null);
        reopen(f, id, f.admin(), "\"3\"", "Fee schedule says 25, not 50");

        List<Map<String, Object>> timeline = jdbc.queryForList(
                "SELECT action, actor_id, payload->>'version' AS version FROM audit_event"
                        + " WHERE organization_id = ? AND entity_type = 'EXCEPTION_CASE' AND entity_id = ?"
                        + " ORDER BY created_at, id",
                f.org,
                id);
        assertThat(timeline)
                .extracting(e -> e.get("action"))
                .containsExactly(
                        "EXCEPTION_CREATED",
                        "EXCEPTION_ASSIGNED",
                        "EXCEPTION_STATUS_CHANGED",
                        "EXCEPTION_NOTE_ADDED",
                        "EXCEPTION_RESOLVED",
                        "EXCEPTION_REOPENED");
        assertThat(timeline)
                .extracting(e -> e.get("actor_id"))
                .containsExactly(
                        null, f.investigator(), f.investigator(), f.investigator(), f.investigator(), f.admin());
        // Each versioned change records the version it produced: the ETag the client got back.
        assertThat(timeline).extracting(e -> e.get("version")).containsExactly(null, "1", "2", null, "3", "4");
        assertThat(f.version(id)).isEqualTo(4);
    }

    @Test
    void aFailureAfterTheChangeRollsBackTheChangeTheHistoryAndTheAudit() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        long audits = f.auditRows(id);
        long history = f.historyRows(id);
        hook.failOnceBeforeCommit(id);

        MockHttpServletResponse res = resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null);

        assertThat(res.getStatus()).isEqualTo(500);
        assertThat(f.status(id)).isEqualTo("OPEN");
        assertThat(f.version(id)).isZero();
        assertThat(f.caseRow(id)).containsEntry("resolution_code", null);
        assertThat(f.historyRows(id)).isEqualTo(history);
        assertThat(f.auditRows(id)).isEqualTo(audits);
        // And the next attempt, at the same version, simply works.
        assertThat(resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null)
                        .getStatus())
                .isEqualTo(200);
    }
}
