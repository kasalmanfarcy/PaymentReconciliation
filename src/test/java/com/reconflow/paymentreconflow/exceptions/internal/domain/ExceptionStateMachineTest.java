package com.reconflow.paymentreconflow.exceptions.internal.domain;

import static com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.IN_REVIEW;
import static com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.OPEN;
import static com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.security.authz.RolePermissions;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/**
 * M7.5: the whole state machine with plain objects. Every (status, transition) cell, every (role,
 * transition) pair against the permission matrix, and each reason rule.
 */
class ExceptionStateMachineTest {

    static final Instant NOW = Instant.parse("2026-11-16T09:00:00Z");
    static final UUID ACTOR = UUID.randomUUID();

    final List<ExceptionStatusHistory> historyRows = new ArrayList<>();
    final AuditService audit = mock(AuditService.class);

    ExceptionStateMachine machine(Set<Permission> permissions) {
        return new ExceptionStateMachine(
                row -> {
                    historyRows.add(row);
                    return row;
                },
                audit,
                tenant(permissions),
                user(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    ExceptionStateMachine admin() {
        return machine(RolePermissions.of(Role.ORG_ADMIN));
    }

    static ExceptionCase caseIn(ExceptionStatus status) {
        ExceptionCase c = ExceptionCase.forTest(UUID.randomUUID(), UUID.randomUUID(), status, 7);
        if (status == RESOLVED) {
            c.markResolved(ResolutionCode.GATEWAY_FEE, "fee", UUID.randomUUID(), NOW);
        }
        return c;
    }

    /** Valid input for each transition, so only the cell under test can fail. */
    static TransitionInput validInput(Transition t) {
        return switch (t.reasonRule()) {
            case NONE -> TransitionInput.NONE;
            case REQUIRED -> TransitionInput.reason("because");
            case RESOLUTION -> TransitionInput.resolution("OTHER", "explained");
        };
    }

    // ---- every (status, transition) cell: 3 × 4 = 12 -------------------------------------------

    /** reconciliation-rules.md §7.3, written out independently of the enum. */
    static final Map<ExceptionStatus, Set<Transition>> ALLOWED = Map.of(
            OPEN, Set.of(Transition.START_REVIEW, Transition.RESOLVE),
            IN_REVIEW, Set.of(Transition.RETURN_TO_OPEN, Transition.RESOLVE),
            RESOLVED, Set.of(Transition.REOPEN));

    static Stream<Arguments> everyCell() {
        return Stream.of(ExceptionStatus.values())
                .flatMap(s -> Stream.of(Transition.values()).map(t -> Arguments.of(s, t)));
    }

    @ParameterizedTest(name = "{0} --{1}-->")
    @MethodSource("everyCell")
    void theTableDecides(ExceptionStatus status, Transition t) {
        ExceptionCase c = caseIn(status);

        if (ALLOWED.get(status).contains(t)) {
            admin().apply(c, t, validInput(t));

            assertThat(c.getStatus()).isEqualTo(t.to());
            assertThat(historyRows).singleElement().satisfies(h -> {
                assertThat(h.getFromStatus()).isEqualTo(status);
                assertThat(h.getToStatus()).isEqualTo(t.to());
                assertThat(h.getActorId()).isEqualTo(ACTOR);
            });
            ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).record(entry.capture());
            assertThat(entry.getValue().action()).isEqualTo(t.auditAction());
            assertThat(entry.getValue().payload()).containsEntry("version", 8L);
        } else {
            assertThatThrownBy(() -> admin().apply(c, t, validInput(t)))
                    .isInstanceOf(InvalidTransitionException.class)
                    .satisfies(e -> assertThat(((InvalidTransitionException) e).properties())
                            .containsEntry("currentStatus", status.name()));
            assertThat(c.getStatus()).isEqualTo(status);
            assertThat(historyRows).isEmpty();
            verify(audit, never()).record(any());
        }
    }

    // ---- every (role, transition) pair against the matrix --------------------------------------

    static Stream<Arguments> everyRoleAndTransition() {
        return Stream.of(Role.values())
                .flatMap(r -> Stream.of(Transition.values()).map(t -> Arguments.of(r, t)));
    }

    /** api-design.md §9, hard-coded on purpose: a change to RolePermissions must break this test. */
    static final Map<Role, Set<Transition>> MAY = Map.of(
            Role.VIEWER, Set.of(),
            Role.UPLOADER, Set.of(),
            Role.INVESTIGATOR, Set.of(Transition.START_REVIEW, Transition.RETURN_TO_OPEN, Transition.RESOLVE),
            Role.ORG_ADMIN, Set.of(Transition.values()));

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyRoleAndTransition")
    void thePermissionMatrixDecides(Role role, Transition t) {
        ExceptionStatus from = Stream.of(ExceptionStatus.values())
                .filter(t::allowedFrom)
                .findFirst()
                .orElseThrow();
        ExceptionCase c = caseIn(from);
        ExceptionStateMachine machine = machine(RolePermissions.of(role));

        if (MAY.get(role).contains(t)) {
            machine.apply(c, t, validInput(t));
            assertThat(c.getStatus()).isEqualTo(t.to());
        } else {
            assertThatThrownBy(() -> machine.apply(c, t, validInput(t))).isInstanceOf(AccessDeniedException.class);
            assertThat(c.getStatus()).isEqualTo(from);
            assertThat(historyRows).isEmpty();
            verify(audit, never()).record(any());
        }
    }

    // ---- reason rules ----------------------------------------------------------------------------

    @Test
    void backToOpenNeedsAReason() {
        assertRejected(
                caseIn(IN_REVIEW), Transition.RETURN_TO_OPEN, TransitionInput.reason("  "), "reason", "required");
        assertRejected(
                caseIn(IN_REVIEW),
                Transition.RETURN_TO_OPEN,
                TransitionInput.reason("x".repeat(1001)),
                "reason",
                "size");
    }

    @Test
    void reopenNeedsAReason() {
        assertRejected(caseIn(RESOLVED), Transition.REOPEN, TransitionInput.NONE, "reason", "required");
    }

    @Test
    void resolveNeedsAKnownCode() {
        assertRejected(
                caseIn(OPEN), Transition.RESOLVE, TransitionInput.resolution(null, null), "resolutionCode", "required");
        assertRejected(
                caseIn(OPEN),
                Transition.RESOLVE,
                TransitionInput.resolution("BANANA", null),
                "resolutionCode",
                "unknown-resolution-code");
    }

    @Test
    void someCodesNeedANote() {
        for (ResolutionCode code : ResolutionCode.values()) {
            historyRows.clear();
            ExceptionCase c = caseIn(OPEN);
            TransitionInput noNote = TransitionInput.resolution(code.name(), " ");
            if (code.noteRequired()) {
                assertRejected(c, Transition.RESOLVE, noNote, "note", "required-for-code");
            } else {
                admin().apply(c, Transition.RESOLVE, noNote);
                assertThat(c.getResolutionCode()).isEqualTo(code);
                assertThat(c.getResolutionNote())
                        .as("a blank note is stored as no note")
                        .isNull();
            }
        }
        assertRejected(
                caseIn(OPEN),
                Transition.RESOLVE,
                TransitionInput.resolution("OTHER", "x".repeat(4001)),
                "note",
                "size");
    }

    @Test
    void startReviewIgnoresAReason() {
        ExceptionCase c = caseIn(OPEN);
        admin().apply(c, Transition.START_REVIEW, TransitionInput.reason("not needed"));

        assertThat(historyRows.getFirst().getReason()).isNull();
    }

    private void assertRejected(ExceptionCase c, Transition t, TransitionInput in, String field, String code) {
        ExceptionStatus before = c.getStatus();
        assertThatThrownBy(() -> admin().apply(c, t, in))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> assertThat(
                                ((ValidationFailedException) e).properties().toString())
                        .contains("field=" + field)
                        .contains("code=" + code));
        assertThat(c.getStatus()).isEqualTo(before);
        assertThat(historyRows).isEmpty();
    }

    // ---- effects ----------------------------------------------------------------------------------

    @Test
    void resolveSetsAllFourResolutionFields() {
        ExceptionCase c = caseIn(IN_REVIEW);
        admin().apply(c, Transition.RESOLVE, TransitionInput.resolution("gateway_fee", "50 AED fee"));

        assertThat(c.getResolutionCode()).isEqualTo(ResolutionCode.GATEWAY_FEE);
        assertThat(c.getResolutionNote()).isEqualTo("50 AED fee");
        assertThat(c.getResolvedBy()).isEqualTo(ACTOR);
        assertThat(c.getResolvedAt()).isEqualTo(NOW);
        assertThat(historyRows.getFirst().getResolutionCode()).isEqualTo(ResolutionCode.GATEWAY_FEE);
    }

    @Test
    void reopenClearsTheResolutionAndKeepsItInHistory() {
        ExceptionCase c = caseIn(RESOLVED);
        admin().apply(c, Transition.REOPEN, TransitionInput.reason("fee is 25, not 50"));

        assertThat(c.getResolutionCode()).isNull();
        assertThat(c.getResolutionNote()).isNull();
        assertThat(c.getResolvedBy()).isNull();
        assertThat(c.getResolvedAt()).isNull();
        assertThat(historyRows.getFirst().getResolutionCode()).isEqualTo(ResolutionCode.GATEWAY_FEE);
        assertThat(historyRows.getFirst().getResolutionNote()).isEqualTo("fee");
        assertThat(historyRows.getFirst().getReason()).isEqualTo("fee is 25, not 50");
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().payload())
                .containsEntry("previousResolutionCode", "GATEWAY_FEE")
                .containsEntry("reason", "fee is 25, not 50");
    }

    // ---- assignment ------------------------------------------------------------------------------

    @Test
    void assigningAuditsOnlyARealChange() {
        ExceptionCase c = caseIn(OPEN);
        UUID ravi = UUID.randomUUID();

        assertThat(admin().assign(c, ravi)).isTrue();
        assertThat(admin().assign(c, ravi)).isFalse();

        assertThat(c.getAssignedTo()).isEqualTo(ravi);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.EXCEPTION_ASSIGNED);
        assertThat(entry.getValue().payload()).containsEntry("from", null).containsEntry("to", ravi);
        assertThat(historyRows).as("an assignment is not a status change").isEmpty();
    }

    @Test
    void aResolvedCaseCantBeAssigned() {
        ExceptionCase c = caseIn(RESOLVED);

        assertThatThrownBy(() -> admin().assign(c, UUID.randomUUID())).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> admin().assign(c, null)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void assigningNeedsExceptionUpdate() {
        assertThatThrownBy(() -> machine(RolePermissions.of(Role.VIEWER)).assign(caseIn(OPEN), UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- fakes -------------------------------------------------------------------------------------

    static TenantContext tenant(Set<Permission> permissions) {
        UUID org = UUID.randomUUID();
        return new TenantContext() {
            @Override
            public UUID organizationId() {
                return org;
            }

            @Override
            public Optional<UUID> organizationIdIfPresent() {
                return Optional.of(org);
            }

            @Override
            public Role role() {
                return null;
            }

            @Override
            public boolean has(Permission permission) {
                return permissions.contains(permission);
            }
        };
    }

    static CurrentUser user() {
        return new CurrentUser() {
            @Override
            public UUID id() {
                return ACTOR;
            }

            @Override
            public Optional<UUID> idIfPresent() {
                return Optional.of(ACTOR);
            }
        };
    }
}
