package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.16: each role can do exactly what the matrix allows on a case. The expected statuses are
 * hard-coded on purpose, not derived from RolePermissions: for a security matrix the test is the
 * specification, so changing a role's rights must break it and force a conscious decision.
 * (AuthorizationMatrixIT checks the same endpoints against RolePermissions.)
 */
class ExceptionRoleMatrixIT extends AbstractExceptionApiIT {

    enum Action {
        LIST,
        DETAIL,
        PATCH,
        NOTE,
        RESOLVE,
        REOPEN,
        AUDIT_TIMELINE
    }

    /** phase-M7 §M7.16, api-design.md §9. */
    static final Map<Action, Map<Role, Integer>> EXPECTED = Map.of(
            Action.LIST, Map.of(Role.VIEWER, 200, Role.UPLOADER, 200, Role.INVESTIGATOR, 200, Role.ORG_ADMIN, 200),
            Action.DETAIL, Map.of(Role.VIEWER, 200, Role.UPLOADER, 200, Role.INVESTIGATOR, 200, Role.ORG_ADMIN, 200),
            Action.PATCH, Map.of(Role.VIEWER, 403, Role.UPLOADER, 403, Role.INVESTIGATOR, 200, Role.ORG_ADMIN, 200),
            Action.NOTE, Map.of(Role.VIEWER, 403, Role.UPLOADER, 403, Role.INVESTIGATOR, 201, Role.ORG_ADMIN, 201),
            Action.RESOLVE, Map.of(Role.VIEWER, 403, Role.UPLOADER, 403, Role.INVESTIGATOR, 200, Role.ORG_ADMIN, 200),
            Action.REOPEN, Map.of(Role.VIEWER, 403, Role.UPLOADER, 403, Role.INVESTIGATOR, 403, Role.ORG_ADMIN, 200),
            Action.AUDIT_TIMELINE,
                    Map.of(Role.VIEWER, 403, Role.UPLOADER, 403, Role.INVESTIGATOR, 403, Role.ORG_ADMIN, 200));

    static Stream<Arguments> everyRoleAndAction() {
        return Stream.of(Action.values()).flatMap(a -> Stream.of(Role.values()).map(r -> Arguments.of(a, r)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("everyRoleAndAction")
    void theMatrixDecides(Action action, Role role) throws Exception {
        var f = fixture();
        UUID open = f.amountMismatch("PAY-103");
        UUID resolved = resolvedCase(f);

        MockHttpServletResponse res = perform(action, f, f.member(role), open, resolved);

        int expected = EXPECTED.get(action).get(role);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(expected);
        if (expected == 403) {
            assertThat(code(json(res))).isEqualTo("forbidden");
            assertThat(f.version(open)).isZero();
            assertThat(f.status(resolved)).isEqualTo("RESOLVED");
        }
    }

    @Test
    void aPlatformAdminGets404OnEveryExceptionEndpoint() throws Exception {
        // authentication.plan §5: a platform admin never satisfies an org-scoped permission.
        var f = fixture();
        UUID open = f.amountMismatch("PAY-103");
        UUID resolved = resolvedCase(f);
        UUID platformAdmin = data().newPlatformAdmin().id();

        for (Action action : Action.values()) {
            MockHttpServletResponse res = perform(action, f, platformAdmin, open, resolved);
            assertThat(res.getStatus()).as(action.name()).isEqualTo(404);
            assertThat(code(json(res))).isEqualTo("organization-not-found");
        }
        assertThat(f.version(open)).isZero();
    }

    private MockHttpServletResponse perform(Action action, ExceptionFixture f, UUID user, UUID open, UUID resolved)
            throws Exception {
        return switch (action) {
            case LIST -> list(f, user, "");
            case DETAIL -> detail(f, open, user);
            case PATCH -> patchCase(f, open, user, "\"0\"", "{\"status\":\"IN_REVIEW\"}");
            case NOTE -> addNote(f, open, user, "checked");
            case RESOLVE -> resolve(f, open, user, "\"0\"", "GATEWAY_FEE", null);
            case REOPEN -> reopen(f, resolved, user, "\"1\"", "the fee was wrong");
            case AUDIT_TIMELINE ->
                perform(get("/api/v1/audit-events?entityType=EXCEPTION_CASE&entityId=" + open), f, user);
        };
    }

    /** A second discrepancy, resolved through the API by the investigator (so at version 1). */
    private UUID resolvedCase(ExceptionFixture f) throws Exception {
        f.ledger("PAY-777", "10.00");
        f.reconcile();
        UUID id = f.caseId("MISSING_IN_GATEWAY:PAY-777:PAYMENT:AED");
        MockHttpServletResponse res = resolve(f, id, f.investigator(), "\"0\"", "TIMING_DIFFERENCE", null);
        assertThat(res.getStatus()).isEqualTo(200);
        return id;
    }
}
