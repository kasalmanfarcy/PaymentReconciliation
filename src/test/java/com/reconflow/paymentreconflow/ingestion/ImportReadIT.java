package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M5.17 and M5.18: reading one import, its row errors, and the list. */
class ImportReadIT extends AbstractImportIT {

    @Test
    void aFinishedBatchShowsItsCountsWithoutRetryAfter() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-malformed.csv", sample("ledger-malformed.csv"), "INTERNAL_LEDGER");
        runQueue(t);

        MockHttpServletResponse res = getAs(t, "/api/v1/imports/" + batch);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("Retry-After")).isNull();
        assertThat(json(res))
                .containsEntry("id", batch.toString())
                .containsEntry("status", "COMPLETED_WITH_ERRORS")
                .containsEntry("totalRows", 9)
                .containsEntry("validRows", 1)
                .containsEntry("errorRows", 8)
                .containsEntry("storedErrors", 8)
                .containsEntry("failureReason", null)
                .containsKeys("createdAt", "startedAt", "completedAt");
    }

    @Test
    void aBatchStillInProgressTellsTheClientWhenToPollAgain() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");

        MockHttpServletResponse res = getAs(t, "/api/v1/imports/" + batch);

        assertThat(json(res)).containsEntry("status", "RECEIVED");
        assertThat(res.getHeader("Retry-After")).isEqualTo("2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowErrorsPageInRowOrder() throws Exception {
        Tenant t = newTenant();
        UUID batch = uploadOk(t, "ledger-malformed.csv", sample("ledger-malformed.csv"), "INTERNAL_LEDGER");
        runQueue(t);

        Map<String, Object> first = json(getAs(t, "/api/v1/imports/" + batch + "/errors?size=5"));
        Map<String, Object> second = json(getAs(t, "/api/v1/imports/" + batch + "/errors?size=5&page=1"));

        List<Map<String, Object>> page1 = (List<Map<String, Object>>) first.get("content");
        List<Map<String, Object>> page2 = (List<Map<String, Object>>) second.get("content");
        assertThat(page1).extracting(e -> e.get("rowNumber")).containsExactly(2, 3, 4, 5, 6);
        assertThat(page2).extracting(e -> e.get("rowNumber")).containsExactly(7, 8, 9);
        assertThat(page1.getFirst())
                .containsEntry("column", "amount")
                .containsEntry("code", "INVALID_AMOUNT")
                .containsEntry("rawRow", "PAY-402,\"1,000.00\",AED,SUCCESS,2026-10-05");
        assertThat(page2.getLast())
                .containsEntry("column", null)
                .containsEntry("code", "MALFORMED_ROW")
                .containsEntry("rawRow", "PAY-409,\"60.00,AED,SUCCESS,2026-10-05");
        assertThat((Map<String, Object>) first.get("page"))
                .containsEntry("size", 5)
                .containsEntry("number", 0)
                .containsEntry("totalElements", 8)
                .containsEntry("totalPages", 2);
    }

    @Test
    void anotherOrganizationsBatchIsNotFoundLikeAnUnknownOne() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        UUID theirs = uploadOk(b, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");

        MockHttpServletResponse foreign = getAs(a, "/api/v1/imports/" + theirs);
        MockHttpServletResponse unknown = getAs(a, "/api/v1/imports/" + UUID.randomUUID());

        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(json(foreign)).containsEntry("code", "not-found");
        assertThat(json(unknown)).containsEntry("code", "not-found");
    }

    protected MockHttpServletResponse getAs(Tenant t, String url) throws Exception {
        return mockMvc.perform(get(url).with(as(t.uploader())).with(org(t.org())))
                .andReturn()
                .getResponse();
    }
}
