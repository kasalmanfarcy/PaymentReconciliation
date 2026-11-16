package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.18, beyond the shared CrossTenantIT registry (ExceptionCrossTenantCases): the checks that need
 * a case in org A too. Org B's user can't be assigned to org A's case, and every write aimed at
 * org B's case answers exactly like one aimed at an id that never existed, leaving it untouched.
 */
class ExceptionCrossTenantIT extends AbstractExceptionApiIT {

    @Test
    void anotherOrganizationsUserCantBeAssigned() throws Exception {
        var a = fixture();
        var b = fixture();
        UUID caseA = a.amountMismatch("PAY-103");

        MockHttpServletResponse res =
                patchCase(a, caseA, a.admin(), "\"0\"", "{\"assignedTo\":\"" + b.investigator() + "\"}");

        assertThat(res.getStatus()).isEqualTo(422);
        assertThat(code(json(res))).isEqualTo("invalid-assignee");
        assertThat(a.version(caseA)).isZero();
    }

    interface Write {
        MockHttpServletResponse send(ExceptionFixture caller, UUID target) throws Exception;
    }

    @Test
    void writesToAnotherTenantsCaseAreIndistinguishableFromUnknownIds() throws Exception {
        var a = fixture();
        var b = fixture();
        UUID caseB = b.amountMismatch("PAY-103");
        UUID resolvedB = b.amountMismatch("PAY-104");
        resolve(b, resolvedB, b.investigator(), "\"0\"", "GATEWAY_FEE", null);
        Map<String, Object> before = b.caseRow(caseB);
        Map<String, Object> resolvedBefore = b.caseRow(resolvedB);
        long audits = b.auditRows(caseB) + b.auditRows(resolvedB);
        long history = b.historyRows(caseB) + b.historyRows(resolvedB);

        List<Write> writes = List.of(
                (f, id) -> patchCase(f, id, f.admin(), "\"0\"", "{\"status\":\"IN_REVIEW\"}"),
                (f, id) -> addNote(f, id, f.admin(), "planted"),
                (f, id) -> resolve(f, id, f.admin(), "\"0\"", "GATEWAY_FEE", null),
                (f, id) -> reopen(f, id.equals(caseB) ? resolvedB : id, f.admin(), "\"1\"", "hijack"));
        for (Write write : writes) {
            UUID unknown = UUID.randomUUID();
            MockHttpServletResponse foreign = write.send(a, caseB);
            MockHttpServletResponse missing = write.send(a, unknown);
            assertThat(foreign.getStatus()).isEqualTo(missing.getStatus()).isEqualTo(404);
            assertThat(normalize(foreign.getContentAsString()))
                    .isEqualTo(normalize(missing.getContentAsString()))
                    .contains("\"code\":\"not-found\"");
        }

        assertThat(b.caseRow(caseB)).isEqualTo(before);
        assertThat(b.caseRow(resolvedB)).isEqualTo(resolvedBefore);
        assertThat(b.auditRows(caseB) + b.auditRows(resolvedB)).isEqualTo(audits);
        assertThat(b.historyRows(caseB) + b.historyRows(resolvedB)).isEqualTo(history);
        assertThat(b.count("SELECT count(*) FROM exception_note WHERE organization_id = ?", b.org))
                .isZero();
    }

    /** The ids and the requestId are the only permitted differences. */
    private static String normalize(String body) {
        return body.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}")
                .replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"-\"");
    }
}
