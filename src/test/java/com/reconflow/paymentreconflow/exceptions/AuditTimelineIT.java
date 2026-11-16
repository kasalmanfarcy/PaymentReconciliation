package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.13: an admin reads one case's audit timeline, oldest first, including assignments and notes,
 * which the status history doesn't hold. Lives with the exception tests because it drives the
 * exception workflow to produce events.
 */
class AuditTimelineIT extends AbstractExceptionApiIT {

    static final String AUDIT = "/api/v1/audit-events";

    MockHttpServletResponse timeline(ExceptionFixture f, UUID user, String query) throws Exception {
        return perform(get(AUDIT + "?" + query), f, user);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAdminSeesTheWholeTimelineOfACase() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        patchCase(f, id, f.investigator(), "\"0\"", "{\"assignedTo\":\"" + f.investigator() + "\"}");
        addNote(f, id, f.investigator(), "checked");
        resolve(f, id, f.investigator(), "\"1\"", "GATEWAY_FEE", null);

        MockHttpServletResponse res = timeline(f, f.admin(), "entityType=EXCEPTION_CASE&entityId=" + id);

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        List<Map<String, Object>> events = content(res);
        assertThat(events)
                .extracting(e -> e.get("action"))
                .containsExactly(
                        "EXCEPTION_CREATED", "EXCEPTION_ASSIGNED", "EXCEPTION_NOTE_ADDED", "EXCEPTION_RESOLVED");
        assertThat(events.getFirst()).containsEntry("actorId", null);
        assertThat(events.get(1)).containsEntry("actorId", f.investigator().toString());
        assertThat((Map<String, Object>) events.get(1).get("payload"))
                .containsEntry("to", f.investigator().toString());
        assertThat((Map<String, Object>) events.getLast().get("payload"))
                .containsEntry("resolutionCode", "GATEWAY_FEE");
        assertThat(events.get(1).get("requestId")).isNotNull();
        assertThat(events.get(1).get("at")).isNotNull();
        assertThat((Map<String, Object>) json(res).get("page")).containsEntry("totalElements", 4);
    }

    @Test
    void pagesAreOldestFirst() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        addNote(f, id, f.investigator(), "one");
        addNote(f, id, f.investigator(), "two");

        List<Map<String, Object>> page1 =
                content(timeline(f, f.admin(), "entityType=EXCEPTION_CASE&entityId=" + id + "&size=2&page=1"));

        assertThat(page1).extracting(e -> e.get("action")).containsExactly("EXCEPTION_NOTE_ADDED");
    }

    @Test
    void onlyAdminsReadTheAuditLog() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        for (Role role : new Role[] {Role.VIEWER, Role.UPLOADER, Role.INVESTIGATOR}) {
            MockHttpServletResponse res = timeline(f, f.member(role), "entityType=EXCEPTION_CASE&entityId=" + id);
            assertThat(res.getStatus()).as(role.name()).isEqualTo(403);
        }
    }

    @Test
    void bothParametersAreRequiredAndChecked() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        for (String query : new String[] {
            "entityType=EXCEPTION_CASE",
            "entityId=" + id,
            "entityType=BANANA&entityId=" + id,
            "entityType=EXCEPTION_CASE&entityId=x"
        }) {
            MockHttpServletResponse res = timeline(f, f.admin(), query);
            assertThat(res.getStatus()).as(query).isEqualTo(400);
            assertThat(code(json(res))).isEqualTo("validation-failed");
        }
    }

    @Test
    void anotherOrganizationsCaseHasAnEmptyTimeline() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        var other = fixture();

        MockHttpServletResponse res = timeline(other, other.admin(), "entityType=EXCEPTION_CASE&entityId=" + id);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(content(res)).isEmpty();
    }
}
