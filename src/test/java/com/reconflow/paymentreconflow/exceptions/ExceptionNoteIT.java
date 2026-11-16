package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M7.9: notes are appended in any status, never bump the case version, and appear in order. */
class ExceptionNoteIT extends AbstractExceptionApiIT {

    @Test
    @SuppressWarnings("unchecked")
    void notesAppearInTheDetailInOrder() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        MockHttpServletResponse first = addNote(f, id, f.investigator(), "Asked gateway support. Ticket GW-8812.");
        MockHttpServletResponse second = addNote(f, id, f.admin(), "  Gateway confirms a 50 AED fee.  ");

        assertThat(first.getStatus()).isEqualTo(201);
        Map<String, Object> note = json(first);
        assertThat(first.getHeader("Location")).isEqualTo(EXCEPTIONS + "/" + id + "/notes/" + note.get("id"));
        assertThat((Map<String, Object>) note.get("author"))
                .containsEntry("id", f.investigator().toString());
        assertThat(second.getStatus()).isEqualTo(201);

        List<Map<String, Object>> notes =
                (List<Map<String, Object>>) json(detail(f, id, f.viewer())).get("notes");
        assertThat(notes)
                .extracting(n -> n.get("body"))
                // Kept exactly as written, spaces included: a note is evidence.
                .containsExactly("Asked gateway support. Ticket GW-8812.", "  Gateway confirms a 50 AED fee.  ");
        assertThat(f.auditRows(id, "EXCEPTION_NOTE_ADDED")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'noteId' FROM audit_event WHERE entity_id = ? AND action = 'EXCEPTION_NOTE_ADDED'"
                                + " ORDER BY created_at, id LIMIT 1",
                        String.class,
                        id))
                .isEqualTo(note.get("id"));
    }

    @Test
    void aNoteNeitherNeedsNorChangesTheVersion() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        addNote(f, id, f.investigator(), "first");
        addNote(f, id, f.investigator(), "second");

        assertThat(f.version(id)).isZero();
        assertThat(detail(f, id, f.viewer()).getHeader("ETag")).isEqualTo("\"0\"");
    }

    @Test
    void aResolvedCaseStillTakesNotes() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        jdbc.update(
                "UPDATE exception_case SET status = 'RESOLVED', resolution_code = 'GATEWAY_FEE', resolved_by = ?,"
                        + " resolved_at = now() WHERE id = ?",
                f.investigator(),
                id);

        assertThat(addNote(f, id, f.investigator(), "Refund confirmed on 12 Nov")
                        .getStatus())
                .isEqualTo(201);
    }

    @Test
    void blankOrOversizedNotesAreRejected() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        for (String body : new String[] {"", "   ", "x".repeat(4001)}) {
            MockHttpServletResponse res = addNote(f, id, f.investigator(), body);
            assertThat(res.getStatus()).isEqualTo(400);
            assertThat(code(json(res))).isEqualTo("validation-failed");
        }
        assertThat(f.count("SELECT count(*) FROM exception_note WHERE exception_id = ?", id))
                .isZero();
    }

    @Test
    void viewersAndUploadersCantAddNotes() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        assertThat(addNote(f, id, f.viewer(), "x").getStatus()).isEqualTo(403);
        assertThat(addNote(f, id, f.uploader(), "x").getStatus()).isEqualTo(403);
        assertThat(addNote(f, id, f.investigator(), "x").getStatus()).isEqualTo(201);
    }

    @Test
    void aNoteOnAnUnknownCaseIs404() throws Exception {
        var f = fixture();

        MockHttpServletResponse res = addNote(f, UUID.randomUUID(), f.investigator(), "x");

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(code(json(res))).isEqualTo("not-found");
    }
}
