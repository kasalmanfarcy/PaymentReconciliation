package com.reconflow.paymentreconflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M5.19: every filter, the date-range rule, accepted batches only, and the slice envelope. */
class TransactionSearchIT extends AbstractIntegrationTest {

    static final String OCTOBER = "transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31";

    UUID org;
    UUID user;
    UUID gatewayBatch;
    UUID ledgerBatch;
    UUID failedBatch;
    UUID processingBatch;

    @BeforeEach
    void records() {
        org = data().newOrganization();
        user = data().newMember(org, Role.VIEWER).id();
        gatewayBatch = data().newImportBatch(org, user, "GATEWAY", "COMPLETED");
        ledgerBatch = data().newImportBatch(org, user, "INTERNAL_LEDGER", "COMPLETED_WITH_ERRORS");
        failedBatch = data().newImportBatch(org, user, "GATEWAY", "FAILED");
        processingBatch = data().newImportBatch(org, user, "GATEWAY", "PROCESSING");
        insert(gatewayBatch, "GATEWAY", 1, "PAY-103", "700.0000", "AED", "2026-10-01");
        insert(gatewayBatch, "GATEWAY", 2, "PAY-201", "108.9200", "USD", "2026-10-03");
        insert(gatewayBatch, "GATEWAY", 3, "PAY 103", "5.0000", "AED", "2026-10-05");
        insert(ledgerBatch, "INTERNAL_LEDGER", 1, "PAY-103", "750.0000", "AED", "2026-10-01");
        insert(ledgerBatch, "INTERNAL_LEDGER", 2, "PAY-999", "1.0000", "AED", "2026-11-01"); // outside October
        insert(failedBatch, "GATEWAY", 1, "PAY-103", "9.0000", "AED", "2026-10-01");
        insert(processingBatch, "GATEWAY", 1, "PAY-103", "8.0000", "AED", "2026-10-01");
    }

    @Test
    void returnsOnlyAcceptedBatchesWithinTheRangeAsASlice() throws Exception {
        Map<String, Object> body = search(OCTOBER);

        assertThat(amounts(body)).containsExactlyInAnyOrder("700.0000", "108.9200", "5.0000", "750.0000");
        assertThat(page(body))
                .containsEntry("number", 0)
                .containsEntry("size", 50)
                .containsEntry("hasNext", false);
        assertThat(page(body)).doesNotContainKeys("totalElements", "totalPages");
        assertThat(content(body).getFirst())
                .doesNotContainKey("rawValues")
                .containsKeys("normalizedReference", "currency");
    }

    @Test
    void filtersBySourceReferenceCurrencyAndBatch() throws Exception {
        assertThat(amounts(search(OCTOBER + "&sourceType=INTERNAL_LEDGER"))).containsExactly("750.0000");
        // N1-N3 on the search term too: full-width, lower case, padded.
        assertThat(amounts(search(OCTOBER + "&sort=amount", "reference", "\uFF50ay-103 ")))
                .containsExactly("700.0000", "750.0000");
        assertThat(amounts(search(OCTOBER, "reference", "pay 103"))).containsExactly("5.0000"); // N4: not PAY-103
        assertThat(amounts(search(OCTOBER + "&currency=usd"))).containsExactly("108.9200");
        assertThat(amounts(search(OCTOBER + "&batchId=" + ledgerBatch))).containsExactly("750.0000");
        assertThat(amounts(search(OCTOBER + "&batchId=" + failedBatch))).isEmpty();
    }

    @Test
    void sortsByWhitelistedFieldsAndPagesWithHasNext() throws Exception {
        Map<String, Object> first = search(OCTOBER + "&sort=amount,desc&size=3");
        Map<String, Object> second = search(OCTOBER + "&sort=amount,desc&size=3&page=1");

        assertThat(amounts(first)).containsExactly("750.0000", "700.0000", "108.9200");
        assertThat(page(first)).containsEntry("hasNext", true);
        assertThat(amounts(second)).containsExactly("5.0000");
        assertThat(page(second)).containsEntry("hasNext", false);
        assertThat(page(search(OCTOBER + "&size=5000"))).containsEntry("size", 200);
    }

    @Test
    void theDateRangeIsRequiredOrderedAndAtMost366Days() throws Exception {
        assertBadRequest(call("transactionDateFrom=2026-10-01"), "transactionDateTo");
        assertBadRequest(call("transactionDateFrom=2026-10-31&transactionDateTo=2026-10-01"), "transactionDateTo");
        assertBadRequest(call("transactionDateFrom=2026-01-01&transactionDateTo=2027-01-02"), "transactionDateTo");
        assertThat(call("transactionDateFrom=2026-01-01&transactionDateTo=2027-01-01")
                        .getStatus())
                .isEqualTo(200);
        assertBadRequest(call(OCTOBER + "&sort=rawValues"), "sort");
    }

    private void assertBadRequest(MockHttpServletResponse res, String field) throws Exception {
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString())
                .contains("\"code\":\"validation-failed\"")
                .contains(field);
    }

    private void insert(UUID batch, String source, int row, String ref, String amount, String currency, String date) {
        jdbc.update("""
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, status, transaction_date, raw_values)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::numeric, ?, 'SUCCESS', ?::date, '{"x":"y"}'::jsonb)
                """, org, batch, source, row, ref, ref, amount, currency, date);
    }

    /** {@code params} are name/value pairs sent unencoded, for values a URL template would mangle. */
    private MockHttpServletResponse call(String query, String... params) throws Exception {
        var request = get("/api/v1/transactions?" + query).with(as(user)).with(org(org));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> search(String query, String... params) throws Exception {
        MockHttpServletResponse res = call(query, params);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("content");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> page(Map<String, Object> body) {
        return (Map<String, Object>) body.get("page");
    }

    private static List<Object> amounts(Map<String, Object> body) {
        return content(body).stream().map(r -> r.get("amount")).toList();
    }
}
