package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M7.10: resolve with a code (and a note where required), once, with If-Match. */
class ExceptionResolveIT extends AbstractExceptionApiIT {

    @Test
    @SuppressWarnings("unchecked")
    void pay103ResolvesAsAGatewayFee() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        patchCase(
                f,
                id,
                f.investigator(),
                "\"0\"",
                "{\"assignedTo\":\"" + f.investigator() + "\",\"status\":\"IN_REVIEW\"}");

        MockHttpServletResponse res = resolve(f, id, f.investigator(), "\"1\"", "GATEWAY_FEE", null);

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"2\"");
        Map<String, Object> body = json(res);
        assertThat(body).containsEntry("status", "RESOLVED").containsEntry("version", 2);
        Map<String, Object> resolution = (Map<String, Object>) body.get("resolution");
        assertThat(resolution).containsEntry("code", "GATEWAY_FEE").containsEntry("note", null);
        assertThat((Map<String, Object>) resolution.get("resolvedBy"))
                .containsEntry("id", f.investigator().toString());
        assertThat(resolution.get("resolvedAt")).isNotNull();
        // Resolving doesn't unassign: the assignee is part of the record of who worked the case.
        assertThat((Map<String, Object>) body.get("assignedTo"))
                .containsEntry("id", f.investigator().toString());
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");
        assertThat(history.getLast())
                .containsEntry("from", "IN_REVIEW")
                .containsEntry("to", "RESOLVED")
                .containsEntry("resolutionCode", "GATEWAY_FEE");
        assertThat(f.auditRows(id, "EXCEPTION_RESOLVED")).isOne();
        assertThat(jdbc.queryForMap(
                        "SELECT payload->>'resolutionCode' AS code, payload->>'version' AS v, actor_id FROM audit_event"
                                + " WHERE entity_id = ? AND action = 'EXCEPTION_RESOLVED'",
                        id))
                .containsEntry("code", "GATEWAY_FEE")
                .containsEntry("v", "2")
                .containsEntry("actor_id", f.investigator());
    }

    @Test
    void anOpenCaseResolvesDirectly() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res =
                resolve(f, id, f.admin(), "\"0\"", "DATA_ENTRY_ERROR", "Ledger corrected upstream");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(f.caseRow(id))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution_code", "DATA_ENTRY_ERROR")
                .containsEntry("resolution_note", "Ledger corrected upstream")
                .containsEntry("resolved_by", f.admin());
    }

    @Test
    void resolvingTwiceIsAnInvalidTransition() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null);

        MockHttpServletResponse again = resolve(f, id, f.investigator(), "\"1\"", "DATA_ENTRY_ERROR", "typo");

        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(json(again))
                .containsEntry("code", "invalid-transition")
                .containsEntry("currentStatus", "RESOLVED")
                .containsEntry("allowedTransitions", List.of("REOPEN"));
        assertThat(f.caseRow(id)).containsEntry("resolution_code", "GATEWAY_FEE");
    }

    @Test
    void codesThatNeedANoteGetOne() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse noNote = resolve(f, id, f.investigator(), "\"0\"", "OTHER", null);
        MockHttpServletResponse unknown = resolve(f, id, f.investigator(), "\"0\"", "BANANA", null);
        MockHttpServletResponse tooLong = resolve(f, id, f.investigator(), "\"0\"", "OTHER", "x".repeat(4001));

        assertThat(noNote.getStatus()).isEqualTo(400);
        assertThat(noNote.getContentAsString()).contains("\"field\":\"note\"").contains("required-for-code");
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(unknown.getContentAsString()).contains("unknown-resolution-code");
        assertThat(tooLong.getStatus()).isEqualTo(400);
        assertThat(f.status(id)).isEqualTo("OPEN");
        assertThat(f.version(id)).isZero();
    }

    @Test
    void ifMatchIsRequiredAndMustBeCurrent() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        assertThat(resolve(f, id, f.investigator(), null, "GATEWAY_FEE", null).getStatus())
                .isEqualTo(428);
        MockHttpServletResponse stale = resolve(f, id, f.investigator(), "\"5\"", "GATEWAY_FEE", null);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(json(stale)).containsEntry("code", "stale-version").containsEntry("currentVersion", 0);
        assertThat(f.status(id)).isEqualTo("OPEN");
    }

    @Test
    void viewersAndUploadersCantResolve() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        assertThat(resolve(f, id, f.viewer(), "\"0\"", "GATEWAY_FEE", null).getStatus())
                .isEqualTo(403);
        assertThat(resolve(f, id, f.uploader(), "\"0\"", "GATEWAY_FEE", null).getStatus())
                .isEqualTo(403);
        assertThat(f.status(id)).isEqualTo("OPEN");
    }
}
