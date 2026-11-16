package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M6.17: the organization's runs, newest first, filtered by status and period overlap, paginated. */
class RunListIT extends AbstractRunApiIT {

    @Test
    void runsAreListedNewestFirstWithFilters() throws Exception {
        var f = new ReconFixture(jdbc);
        UUID september = runToCompletion(f, "2026-09-01", "2026-09-30", "AED");
        UUID october = runToCompletion(f, "2026-10-01", "2026-10-31", "AED");
        UUID pending = UUID.fromString(json(requestRun(f, body("2026-10-01", "2026-10-31", "USD"), null))
                .get("id")
                .toString());

        assertThat(ids(list(f, ""))).containsExactly(pending, october, september);
        assertThat(ids(list(f, "?sort=createdAt,asc"))).containsExactly(september, october, pending);
        assertThat(ids(list(f, "?status=COMPLETED"))).containsExactly(october, september);
        assertThat(ids(list(f, "?status=PENDING&status=RUNNING"))).containsExactly(pending);
        // Period overlap: September 25 - October 2 touches both months' runs.
        assertThat(ids(list(f, "?from=2026-09-25&to=2026-10-02"))).containsExactly(pending, october, september);
        assertThat(ids(list(f, "?from=2026-10-15"))).containsExactly(pending, october);
        assertThat(ids(list(f, "?to=2026-09-30"))).containsExactly(september);

        Map<String, Object> page = json(list(f, "?page=1&size=2"));
        assertThat(page.get("page")).isEqualTo(Map.of("number", 1, "size", 2, "totalElements", 3, "totalPages", 2));
        assertThat(ids(list(f, "?page=1&size=2"))).containsExactly(september);
    }

    @Test
    void unknownSortFieldsAndReversedRangesAre400() throws Exception {
        var f = new ReconFixture(jdbc);

        for (String query :
                List.of("?sort=periodStart", "?sort=createdAt,sideways", "?from=2026-10-02&to=2026-10-01")) {
            MockHttpServletResponse res = list(f, query);
            assertThat(res.getStatus()).as(query).isEqualTo(400);
            assertThat(json(res)).containsEntry("code", "validation-failed");
        }
    }

    private MockHttpServletResponse list(ReconFixture f, String query) throws Exception {
        return getAs(f, RUNS + query);
    }

    @SuppressWarnings("unchecked")
    private List<UUID> ids(MockHttpServletResponse res) throws Exception {
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return ((List<Map<String, Object>>) json(res).get("content"))
                .stream().map(r -> UUID.fromString(r.get("id").toString())).toList();
    }
}
