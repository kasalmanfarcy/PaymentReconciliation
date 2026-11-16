package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M6.16: a run's matches, both records side by side, paginated and in a stable order. */
class RunMatchesIT extends AbstractRunApiIT {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void matchesShowBothRecordsSortedByReference() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID ledger = f.ledger("PAY-2", "300.00", "AED", DAY);
        UUID gateway = f.gateway("PAY-2", "300.0", "AED", DAY.plusDays(1));
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.gateway("PAY-1", "10.00", "AED", DAY);
        f.ledger("PAY-3", "5.00", "AED", DAY); // missing: not a match
        UUID run = runToCompletion(f, "2026-10-01", "2026-10-02", "AED");

        MockHttpServletResponse res = getAs(f, RUNS + "/" + run + "/matches");

        assertThat(res.getStatus()).isEqualTo(200);
        Map<String, Object> page = json(res);
        assertThat(page.get("page")).isEqualTo(Map.of("number", 0, "size", 50, "totalElements", 2, "totalPages", 1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content)
                .extracting(m -> (Object) ((Map<?, ?>) m.get("ledger")).get("normalizedReference"))
                .containsExactly("PAY-1", "PAY-2");
        assertThat(content.get(1))
                .containsEntry("matchType", "EXACT")
                .containsEntry("currency", "AED")
                .containsEntry("amountDifference", "0.0000")
                .containsEntry(
                        "ledger",
                        Map.of(
                                "id", ledger.toString(),
                                "reference", "PAY-2",
                                "normalizedReference", "PAY-2",
                                "amount", "300.0000",
                                "transactionDate", "2026-10-01"))
                .containsEntry(
                        "gateway",
                        Map.of(
                                "id", gateway.toString(),
                                "reference", "PAY-2",
                                "normalizedReference", "PAY-2",
                                "amount", "300.0000",
                                "transactionDate", "2026-10-02"));
    }

    @Test
    void pagesAreStableAndTheSizeIsClamped() throws Exception {
        var f = new ReconFixture(jdbc);
        for (int i = 0; i < 5; i++) {
            f.ledger("PAY-" + i, "1.00", "AED", DAY);
            f.gateway("PAY-" + i, "1.00", "AED", DAY);
        }
        UUID run = runToCompletion(f, "2026-10-01", "2026-10-01", "AED");

        Map<String, Object> second = json(getAs(f, RUNS + "/" + run + "/matches?page=1&size=2"));
        Map<String, Object> huge = json(getAs(f, RUNS + "/" + run + "/matches?size=5000"));

        assertThat(second.get("page")).isEqualTo(Map.of("number", 1, "size", 2, "totalElements", 5, "totalPages", 3));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) second.get("content");
        assertThat(content)
                .extracting(m -> (Object) ((Map<?, ?>) m.get("ledger")).get("reference"))
                .containsExactly("PAY-2", "PAY-3");
        assertThat(((Map<?, ?>) huge.get("page")).get("size")).isEqualTo(200);
    }

    @Test
    void anUnknownRunIs404NotAnEmptyPage() throws Exception {
        var f = new ReconFixture(jdbc);

        MockHttpServletResponse res = getAs(f, RUNS + "/" + UUID.randomUUID() + "/matches");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(json(res)).containsEntry("code", "not-found");
    }
}
