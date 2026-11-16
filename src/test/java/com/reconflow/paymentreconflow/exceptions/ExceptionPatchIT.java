package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** M7.8: assign and review through a JSON Merge Patch guarded by If-Match. */
class ExceptionPatchIT extends AbstractExceptionApiIT {

    @Test
    @SuppressWarnings("unchecked")
    void assignsAndTheETagMovesByOne() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res =
                patchCase(f, id, f.investigator(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"1\"");
        Map<String, Object> body = json(res);
        assertThat((Map<String, Object>) body.get("assignedTo"))
                .containsEntry("id", f.investigator().toString());
        assertThat(body).containsEntry("version", 1).containsEntry("status", "OPEN");
        assertThat(f.caseRow(id)).containsEntry("assigned_to", f.investigator());
        assertThat(f.auditRows(id, "EXCEPTION_ASSIGNED")).isOne();
        assertThat(f.historyRows(id)).as("an assignment is not a status change").isOne();
    }

    @Test
    void unassignsWithNull() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        patchCase(f, id, f.admin(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");

        MockHttpServletResponse res = patchCase(f, id, f.admin(), "\"1\"", "{\"assignedTo\":null}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(json(res)).containsEntry("assignedTo", null);
        assertThat(f.caseRow(id)).containsEntry("assigned_to", null);
    }

    @Test
    void reassigningTheSameUserChangesNothing() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        patchCase(f, id, f.admin(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");

        MockHttpServletResponse res =
                patchCase(f, id, f.admin(), "\"1\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(f.auditRows(id, "EXCEPTION_ASSIGNED")).isOne();
        // ...but the version must still match.
        assertThat(patchCase(f, id, f.admin(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}")
                        .getStatus())
                .isEqualTo(409);
    }

    @Test
    void movesIntoReviewAndBackWithAReason() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse review = patchCase(f, id, f.investigator(), "\"0\"", "{\"status\":\"IN_REVIEW\"}");
        assertThat(review.getStatus()).isEqualTo(200);
        assertThat(json(review)).containsEntry("status", "IN_REVIEW");
        assertThat(review.getHeader("ETag")).isEqualTo("\"1\"");

        MockHttpServletResponse noReason = patchCase(f, id, f.investigator(), "\"1\"", "{\"status\":\"OPEN\"}");
        assertThat(noReason.getStatus()).isEqualTo(400);
        assertThat(code(json(noReason))).isEqualTo("validation-failed");
        assertThat(noReason.getContentAsString()).contains("\"field\":\"reason\"");
        assertThat(f.version(id)).isOne();

        MockHttpServletResponse back = patchCase(
                f, id, f.investigator(), "\"1\"", "{\"status\":\"OPEN\",\"reason\":\"Waiting for gateway support\"}");
        assertThat(back.getStatus()).isEqualTo(200);
        assertThat(back.getHeader("ETag")).isEqualTo("\"2\"");
        assertThat(f.status(id)).isEqualTo("OPEN");
        assertThat(f.historyRows(id)).isEqualTo(3);
        assertThat(f.auditRows(id, "EXCEPTION_STATUS_CHANGED")).isEqualTo(2);
    }

    @Test
    void assigningAndReviewingInOnePatchIsOneVersion() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res = patchCase(
                f,
                id,
                f.investigator(),
                "\"0\"",
                "{\"assignedTo\":\"" + f.investigator() + "\",\"status\":\"IN_REVIEW\"}");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(f.auditRows(id)).isEqualTo(3); // created, assigned, status changed
    }

    @Test
    void ifMatchIsRequiredAndMustBeCurrent() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        String body = "{\"status\":\"IN_REVIEW\"}";

        MockHttpServletResponse missing = patchCase(f, id, f.investigator(), null, body);
        assertThat(missing.getStatus()).isEqualTo(428);
        assertThat(code(json(missing))).isEqualTo("precondition-required");

        patchCase(f, id, f.investigator(), "\"0\"", "{\"assignedTo\":\"" + f.admin() + "\"}");
        MockHttpServletResponse stale = patchCase(f, id, f.investigator(), "\"0\"", body);
        assertThat(stale.getStatus()).isEqualTo(409);
        assertThat(json(stale)).containsEntry("code", "stale-version").containsEntry("currentVersion", 1);
        assertThat(f.status(id)).isEqualTo("OPEN");

        for (String malformed : new String[] {"0", "W/\"1\"", "*", "\"1\", \"2\"", "\"one\""}) {
            MockHttpServletResponse res = patchCase(f, id, f.investigator(), malformed, body);
            assertThat(res.getStatus()).as(malformed).isEqualTo(400);
            assertThat(res.getContentAsString()).contains("invalid-if-match");
        }
        assertThat(f.version(id)).isOne();
    }

    @Test
    void theAssigneeMustBeAnActiveMemberWhoMayWorkCases() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        UUID otherOrgsInvestigator =
                data().newMember(data().newOrganization(), Role.INVESTIGATOR).id();
        var disabled = data().newMember(f.org, Role.INVESTIGATOR);
        data().setStatus(disabled.id(), "DISABLED");

        for (UUID assignee :
                new UUID[] {otherOrgsInvestigator, f.viewer(), f.uploader(), disabled.id(), UUID.randomUUID()}) {
            MockHttpServletResponse res = patchCase(f, id, f.admin(), "\"0\"", "{\"assignedTo\":\"" + assignee + "\"}");
            assertThat(res.getStatus()).isEqualTo(422);
            assertThat(code(json(res))).isEqualTo("invalid-assignee");
            // Never names the user or says whether it exists.
            assertThat(res.getContentAsString()).doesNotContain(assignee.toString());
        }
        assertThat(f.version(id)).isZero();
        assertThat(f.auditRows(id, "EXCEPTION_ASSIGNED")).isZero();
    }

    @Test
    void aResolvedCaseCantBeAssignedOrReviewed() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        jdbc.update(
                "UPDATE exception_case SET status = 'RESOLVED', resolution_code = 'GATEWAY_FEE', resolved_by = ?,"
                        + " resolved_at = now() WHERE id = ?",
                f.investigator(),
                id);

        MockHttpServletResponse assign = patchCase(f, id, f.admin(), "\"0\"", "{\"assignedTo\":\"" + f.admin() + "\"}");
        MockHttpServletResponse review = patchCase(f, id, f.admin(), "\"0\"", "{\"status\":\"IN_REVIEW\"}");
        MockHttpServletResponse open = patchCase(f, id, f.admin(), "\"0\"", "{\"status\":\"OPEN\",\"reason\":\"x\"}");

        for (MockHttpServletResponse res : new MockHttpServletResponse[] {assign, review, open}) {
            assertThat(res.getStatus()).isEqualTo(409);
            assertThat(json(res))
                    .containsEntry("code", "invalid-transition")
                    .containsEntry("currentStatus", "RESOLVED");
        }
        assertThat(f.version(id)).isZero();
    }

    @Test
    void malformedPatchesAreRejected() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse empty = patchCase(f, id, f.investigator(), "\"0\"", "{}");
        assertThat(empty.getStatus()).isEqualTo(400);
        assertThat(empty.getContentAsString()).contains("nothing-to-update");

        MockHttpServletResponse resolve = patchCase(f, id, f.investigator(), "\"0\"", "{\"status\":\"RESOLVED\"}");
        assertThat(resolve.getStatus()).isEqualTo(400);
        assertThat(resolve.getContentAsString())
                .contains("use-resolve-endpoint")
                .contains("/resolve");

        assertThat(patchCase(f, id, f.investigator(), "\"0\"", "{\"status\":\"DONE\"}")
                        .getStatus())
                .isEqualTo(400);
        assertThat(patchCase(f, id, f.investigator(), "\"0\"", "{\"version\":7}")
                        .getStatus())
                .isEqualTo(400);
        assertThat(patchCase(f, id, f.investigator(), "\"0\"", "{\"assignedTo\":\"bob\"}")
                        .getStatus())
                .isEqualTo(400);
        assertThat(patchCase(f, id, f.investigator(), "\"0\"", "[1]").getStatus())
                .isEqualTo(400);
        assertThat(f.version(id)).isZero();
    }

    @Test
    void onlyMergePatchIsAccepted() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res = perform(
                patch(EXCEPTIONS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"0\"")
                        .content("{\"status\":\"IN_REVIEW\"}"),
                f,
                f.investigator());

        assertThat(res.getStatus()).isEqualTo(415);
        assertThat(code(json(res))).isEqualTo("unsupported-media-type");
    }

    @Test
    void viewersCantPatch() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res = patchCase(f, id, f.viewer(), "\"0\"", "{\"status\":\"IN_REVIEW\"}");

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(f.version(id)).isZero();
    }
}
