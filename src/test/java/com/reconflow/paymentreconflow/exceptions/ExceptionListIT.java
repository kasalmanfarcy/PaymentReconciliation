package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.6: each filter alone and in combination, sorting, paging, and tenant scoping. Five cases come
 * out of two real runs; their status, assignee and creation date are then set with SQL, which is
 * test-only seeding (phase-M7 §5), not a path the application has.
 *
 * <pre>
 *   ref    type                 amount  status     assignedTo     run  created
 *   PAY-1  AMOUNT_MISMATCH      750     IN_REVIEW  investigator   1    today
 *   PAY-2  MISSING_IN_GATEWAY   100     RESOLVED   -              1    today
 *   PAY-3  MISSING_IN_LEDGER     20     OPEN       admin          1    2026-10-01
 *   PAY-4  DUPLICATE_IN_SOURCE   50     OPEN       -              1    today
 *   PAY-5  MISSING_IN_GATEWAY   300     OPEN       -              2    today
 * </pre>
 */
class ExceptionListIT extends AbstractExceptionApiIT {

    ExceptionFixture f;
    UUID run1;
    UUID run2;

    @BeforeEach
    void seed() {
        f = fixture();
        f.ledger("PAY-1", "750.00");
        f.gateway("PAY-1", "700.00");
        f.ledger("PAY-2", "100.00");
        f.gateway("PAY-3", "20.00");
        f.ledger("PAY-4", "50.00");
        f.ledger("PAY-4", "50.00");
        run1 = f.reconcile();
        f.record("INTERNAL_LEDGER", "PAY-5", "300.00", ExceptionFixture.DAY.plusDays(1));
        run2 = f.reconcile(ExceptionFixture.DAY.plusDays(1), ExceptionFixture.DAY.plusDays(1));

        jdbc.update(
                "UPDATE exception_case SET status = 'IN_REVIEW', assigned_to = ? WHERE id = ?",
                f.investigator(),
                id("PAY-1"));
        jdbc.update(
                "UPDATE exception_case SET status = 'RESOLVED', resolution_code = 'TIMING_DIFFERENCE',"
                        + " resolved_by = ?, resolved_at = now() WHERE id = ?",
                f.investigator(),
                id("PAY-2"));
        jdbc.update(
                "UPDATE exception_case SET assigned_to = ?, created_at = ? WHERE id = ?",
                f.admin(),
                Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")),
                id("PAY-3"));
    }

    UUID id(String reference) {
        return jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND discrepancy_key LIKE ?",
                UUID.class,
                f.org,
                "%:" + reference + ":PAYMENT:AED");
    }

    List<String> references(String query) throws Exception {
        return references(query, f.investigator());
    }

    List<String> references(String query, UUID user) throws Exception {
        MockHttpServletResponse res = list(f, user, query);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return content(res).stream().map(c -> (String) c.get("reference")).toList();
    }

    @Test
    void theDefaultIsTheWorkQueueNewestFirst() throws Exception {
        assertThat(references("")).containsExactlyInAnyOrder("PAY-1", "PAY-3", "PAY-4", "PAY-5");
        // created_at DESC: PAY-3 was moved back to October.
        assertThat(references("")).last().isEqualTo("PAY-3");
    }

    @Test
    void byStatus() throws Exception {
        assertThat(references("status=RESOLVED")).containsExactly("PAY-2");
        assertThat(references("status=OPEN&status=RESOLVED"))
                .containsExactlyInAnyOrder("PAY-2", "PAY-3", "PAY-4", "PAY-5");
        assertThat(list(f, f.viewer(), "status=BANANA").getStatus()).isEqualTo(400);
    }

    @Test
    void byType() throws Exception {
        assertThat(references("type=AMOUNT_MISMATCH&type=MISSING_IN_LEDGER"))
                .containsExactlyInAnyOrder("PAY-1", "PAY-3");
        assertThat(references("type=MISSING_IN_GATEWAY")).containsExactly("PAY-5");
    }

    @Test
    void byAssignee() throws Exception {
        assertThat(references("assignedTo=me")).containsExactly("PAY-1");
        assertThat(references("assignedTo=me", f.admin())).containsExactly("PAY-3");
        assertThat(references("assignedTo=unassigned")).containsExactlyInAnyOrder("PAY-4", "PAY-5");
        assertThat(references("assignedTo=" + f.admin())).containsExactly("PAY-3");
        assertThat(references("assignedTo=" + UUID.randomUUID())).isEmpty();
        MockHttpServletResponse bad = list(f, f.viewer(), "assignedTo=somebody");
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(code(json(bad))).isEqualTo("validation-failed");
    }

    @Test
    void assigneeShowsTheDisplayName() throws Exception {
        Map<String, Object> pay1 =
                content(list(f, f.viewer(), "assignedTo=" + f.investigator())).getFirst();

        @SuppressWarnings("unchecked")
        Map<String, Object> assignee = (Map<String, Object>) pay1.get("assignedTo");
        assertThat(assignee).containsEntry("id", f.investigator().toString());
        assertThat((String) assignee.get("displayName")).startsWith("User ");
        assertThat(pay1).containsEntry("amount", "750.0000").containsEntry("version", 0);
    }

    @Test
    void byRunMeansEveryRunThatSawTheCase() throws Exception {
        assertThat(references("runId=" + run2)).containsExactly("PAY-5");
        assertThat(references("runId=" + run1)).containsExactlyInAnyOrder("PAY-1", "PAY-3", "PAY-4");
        assertThat(references("runId=" + run1 + "&status=RESOLVED")).containsExactly("PAY-2");
        assertThat(references("runId=" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void byCreationDateInclusive() throws Exception {
        assertThat(references("createdFrom=2026-10-01&createdTo=2026-10-01")).containsExactly("PAY-3");
        assertThat(references("createdTo=2026-10-01")).containsExactly("PAY-3");
        assertThat(references("createdFrom=2026-10-02")).containsExactlyInAnyOrder("PAY-1", "PAY-4", "PAY-5");
        assertThat(list(f, f.viewer(), "createdFrom=2026-10-02&createdTo=2026-10-01")
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void byAmountRange() throws Exception {
        assertThat(references("amountMin=50&amountMax=100")).containsExactly("PAY-4");
        assertThat(references("amountMin=50.0000&amountMax=100&status=OPEN&status=RESOLVED"))
                .containsExactlyInAnyOrder("PAY-2", "PAY-4");
        assertThat(references("amountMin=300")).containsExactlyInAnyOrder("PAY-1", "PAY-5");
        assertThat(list(f, f.viewer(), "amountMin=100&amountMax=50").getStatus())
                .isEqualTo(400);
        assertThat(list(f, f.viewer(), "amountMin=lots").getStatus()).isEqualTo(400);
    }

    @Test
    void filtersCombine() throws Exception {
        assertThat(references("type=MISSING_IN_LEDGER&assignedTo=" + f.admin() + "&amountMax=20&runId=" + run1))
                .containsExactly("PAY-3");
        assertThat(references("type=MISSING_IN_LEDGER&assignedTo=unassigned")).isEmpty();
    }

    @Test
    void sortsOnlyByWhitelistedFields() throws Exception {
        assertThat(references("sort=amount,desc")).containsExactly("PAY-1", "PAY-5", "PAY-4", "PAY-3");
        assertThat(references("sort=amount")).containsExactly("PAY-3", "PAY-4", "PAY-5", "PAY-1");
        assertThat(references("sort=type&sort=amount,desc")).containsExactly("PAY-1", "PAY-4", "PAY-5", "PAY-3");
        for (String bad : List.of("sort=discrepancy_key", "sort=amount;drop table x", "sort=desc")) {
            MockHttpServletResponse res = list(f, f.viewer(), bad);
            assertThat(res.getStatus()).as(bad).isEqualTo(400);
            assertThat(res.getContentAsString()).contains("unsupported-sort-field");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void paginates() throws Exception {
        Map<String, Object> page0 = json(list(f, f.viewer(), "size=3&sort=amount"));
        Map<String, Object> page1 = json(list(f, f.viewer(), "size=3&sort=amount&page=1"));

        assertThat((Map<String, Object>) page0.get("page"))
                .containsEntry("number", 0)
                .containsEntry("size", 3)
                .containsEntry("totalElements", 4)
                .containsEntry("totalPages", 2);
        assertThat((List<?>) page0.get("content")).hasSize(3);
        assertThat((List<?>) page1.get("content")).hasSize(1);
        assertThat((Map<String, Object>) json(list(f, f.viewer(), "size=5000")).get("page"))
                .containsEntry("size", 200);
    }

    @Test
    void anotherOrganizationsCasesNeverAppear() throws Exception {
        var other = fixture();
        other.amountMismatch("PAY-1");

        assertThat(references("type=AMOUNT_MISMATCH")).containsExactly("PAY-1");
        assertThat(content(list(f, f.viewer(), "type=AMOUNT_MISMATCH")).getFirst())
                .containsEntry("id", id("PAY-1").toString());
        // Filtering by the other organization's run or user matches nothing; it never confirms them.
        UUID otherRun = jdbc.queryForObject(
                "SELECT run_id FROM exception_case WHERE organization_id = ?", UUID.class, other.org);
        assertThat(references("runId=" + otherRun)).isEmpty();
        assertThat(references("assignedTo=" + other.investigator())).isEmpty();
    }
}
