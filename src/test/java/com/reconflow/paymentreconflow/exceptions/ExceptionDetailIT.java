package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M7.7: one case with both sides, its runs and history, and an ETag equal to its version. */
class ExceptionDetailIT extends AbstractExceptionApiIT {

    @Test
    @SuppressWarnings("unchecked")
    void pay103ShowsBothRecordsSideBySide() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        UUID run = (UUID) f.caseRow(id).get("run_id");

        MockHttpServletResponse res = detail(f, id, f.viewer());

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("ETag")).isEqualTo("\"0\"");
        Map<String, Object> body = json(res);
        assertThat(body)
                .containsEntry("id", id.toString())
                .containsEntry("type", "AMOUNT_MISMATCH")
                .containsEntry("status", "OPEN")
                .containsEntry("reference", "PAY-103")
                .containsEntry("discrepancyKey", "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED")
                .containsEntry("currency", "AED")
                .containsEntry("amount", "750.0000")
                .containsEntry("amountDifference", "-50.0000")
                .containsEntry("assignedTo", null)
                .containsEntry("resolution", null)
                .containsEntry("runId", run.toString())
                .containsEntry("version", 0);
        Map<String, List<Map<String, Object>>> records = (Map<String, List<Map<String, Object>>>) body.get("records");
        assertThat(records.get("ledger")).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("amount", "750.0000").containsEntry("transactionDate", "2026-10-03");
            // Raw next to normalized: the trailing space and lower case are often the whole story.
            assertThat(r).containsEntry("rawReference", " pay-103 ").containsEntry("normalizedReference", "PAY-103");
            assertThat(r.get("rawValues")).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) r.get("rawValues")).containsEntry("amount", "750.00");
        });
        assertThat(records.get("gateway"))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("amount", "700.0000"));
        assertThat((List<Map<String, Object>>) body.get("runs"))
                .singleElement()
                .satisfies(r ->
                        assertThat(r).containsEntry("runId", run.toString()).containsEntry("created", true));
        assertThat((List<Map<String, Object>>) body.get("history"))
                .singleElement()
                .satisfies(h -> assertThat(h)
                        .containsEntry("from", null)
                        .containsEntry("to", "OPEN")
                        .containsEntry("actor", null));
        assertThat((List<?>) body.get("notes")).isEmpty();
    }

    @Test
    void anotherTenantsCaseIsIndistinguishableFromAnUnknownOne() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        UUID otherOrg = data().newOrganization();
        var stranger = data().newMember(otherOrg, Role.ORG_ADMIN);
        UUID unknown = UUID.randomUUID();

        MockHttpServletResponse foreign = mockMvc.perform(
                        get(EXCEPTIONS + "/" + id).with(as(stranger.id())).with(org(otherOrg)))
                .andReturn()
                .getResponse();
        MockHttpServletResponse missing = mockMvc.perform(
                        get(EXCEPTIONS + "/" + unknown).with(as(stranger.id())).with(org(otherOrg)))
                .andReturn()
                .getResponse();

        assertThat(foreign.getStatus()).isEqualTo(missing.getStatus()).isEqualTo(404);
        assertThat(normalize(foreign.getContentAsString(), id))
                .isEqualTo(normalize(missing.getContentAsString(), unknown))
                .contains("\"code\":\"not-found\"");
    }

    @Test
    void anUnchangedCaseAnswersIfNoneMatchWith304() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse res =
                perform(get(EXCEPTIONS + "/" + id).header("If-None-Match", "\"0\""), f, f.viewer());

        assertThat(res.getStatus()).isEqualTo(304);
    }

    private static String normalize(String body, UUID requested) {
        return body.replace(requested.toString(), "{id}").replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"-\"");
    }
}
