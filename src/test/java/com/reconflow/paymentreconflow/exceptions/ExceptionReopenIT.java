package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M7.11: an org admin reopens a resolved case with a reason; the old resolution stays in history. */
class ExceptionReopenIT extends AbstractExceptionApiIT {

    @Test
    @SuppressWarnings("unchecked")
    void anAdminReopensAndThePreviousResolutionIsKept() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        patchCase(f, id, f.investigator(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");
        resolve(f, id, f.investigator(), "\"1\"", "GATEWAY_FEE", "50 AED fee, GW-8812");

        MockHttpServletResponse res =
                reopen(f, id, f.admin(), "\"2\"", "Fee schedule shows 25 AED, not 50. Remaining 25 unexplained.");

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"3\"");
        Map<String, Object> body = json(res);
        assertThat(body).containsEntry("status", "OPEN").containsEntry("resolution", null);
        // The assignee stays: the person who worked it is the natural owner of the reopened case.
        assertThat((Map<String, Object>) body.get("assignedTo"))
                .containsEntry("id", f.investigator().toString());
        assertThat(f.caseRow(id))
                .containsEntry("resolution_code", null)
                .containsEntry("resolution_note", null)
                .containsEntry("resolved_by", null)
                .containsEntry("resolved_at", null);
        Map<String, Object> reopenRow = ((List<Map<String, Object>>) body.get("history")).getLast();
        assertThat(reopenRow)
                .containsEntry("from", "RESOLVED")
                .containsEntry("to", "OPEN")
                .containsEntry("resolutionCode", "GATEWAY_FEE")
                .containsEntry("resolutionNote", "50 AED fee, GW-8812");
        assertThat((String) reopenRow.get("reason")).contains("25 AED, not 50");
        assertThat((Map<String, Object>) reopenRow.get("actor"))
                .containsEntry("id", f.admin().toString());
        assertThat(jdbc.queryForMap(
                        "SELECT payload->>'previousResolutionCode' AS prev, payload->>'reason' AS reason FROM audit_event"
                                + " WHERE entity_id = ? AND action = 'EXCEPTION_REOPENED'",
                        id))
                .containsEntry("prev", "GATEWAY_FEE");
    }

    @Test
    void aReasonIsRequired() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null);

        for (String reason : new String[] {null, "", "   "}) {
            MockHttpServletResponse res = reopen(f, id, f.admin(), "\"1\"", reason);
            assertThat(res.getStatus()).isEqualTo(400);
            assertThat(res.getContentAsString()).contains("\"field\":\"reason\"");
        }
        assertThat(f.status(id)).isEqualTo("RESOLVED");
    }

    @Test
    void onlyOrgAdminsMayReopen() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null);

        for (UUID user : new UUID[] {f.investigator(), f.uploader(), f.viewer()}) {
            MockHttpServletResponse res = reopen(f, id, user, "\"1\"", "wrong");
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(code(json(res))).isEqualTo("forbidden");
        }
        assertThat(f.status(id)).isEqualTo("RESOLVED");
    }

    @Test
    void anOpenCaseCantBeReopened() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res = reopen(f, id, f.admin(), "\"0\"", "why not");

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json(res)).containsEntry("code", "invalid-transition").containsEntry("currentStatus", "OPEN");
        assertThat(f.version(id)).isZero();
    }

    @Test
    void ifMatchIsRequired() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null);

        assertThat(reopen(f, id, f.admin(), null, "x").getStatus()).isEqualTo(428);
        assertThat(reopen(f, id, f.admin(), "\"0\"", "x").getStatus()).isEqualTo(409);
        assertThat(f.status(id)).isEqualTo("RESOLVED");
    }

    @Test
    void reopeningWhileALaterRunOpenedANewCaseIsRejected() throws Exception {
        var f = fixture();
        UUID resolved = f.amountMismatch("PAY-103");
        resolve(f, resolved, f.investigator(), "\"0\"", "GATEWAY_FEE", null);
        // The discrepancy is still there, so the next run raises a new OPEN case for it (rule K4).
        f.reconcile();
        UUID newer = f.caseId("AMOUNT_MISMATCH:PAY-103:PAYMENT:AED");
        assertThat(newer).isNotEqualTo(resolved);

        MockHttpServletResponse res = reopen(f, resolved, f.admin(), "\"1\"", "fee was wrong");

        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(json(res))
                .containsEntry("code", "active-case-exists")
                .containsEntry("existingCaseId", newer.toString());
        assertThat(f.status(resolved)).isEqualTo("RESOLVED");
        assertThat(f.version(resolved)).isOne();
        assertThat(f.auditRows(resolved, "EXCEPTION_REOPENED")).isZero();
    }
}
