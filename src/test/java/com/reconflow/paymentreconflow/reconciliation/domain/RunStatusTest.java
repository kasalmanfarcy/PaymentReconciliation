package com.reconflow.paymentreconflow.reconciliation.domain;

import static com.reconflow.paymentreconflow.reconciliation.domain.RunStatus.COMPLETED;
import static com.reconflow.paymentreconflow.reconciliation.domain.RunStatus.FAILED;
import static com.reconflow.paymentreconflow.reconciliation.domain.RunStatus.PENDING;
import static com.reconflow.paymentreconflow.reconciliation.domain.RunStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** M6.10: every cell of the run state machine (reconciliation-rules.md §7.2), allowed and forbidden. */
class RunStatusTest {

    static final Set<String> ALLOWED =
            Set.of("PENDING>RUNNING", "PENDING>FAILED", "RUNNING>COMPLETED", "RUNNING>FAILED", "FAILED>PENDING");

    static Stream<Arguments> everyPair() {
        return Stream.of(RunStatus.values())
                .flatMap(from -> Stream.of(RunStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyTheDocumentedTransitionsAreAllowed(RunStatus from, RunStatus to) {
        assertThat(from.canMoveTo(to)).isEqualTo(ALLOWED.contains(from + ">" + to));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void theEntityEnforcesTheSameTable(RunStatus from, RunStatus to) {
        ReconciliationRun run = runIn(from);
        if (ALLOWED.contains(from + ">" + to)) {
            run.transitionTo(to, Instant.now());
            assertThat(run.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> run.transitionTo(to, Instant.now()))
                    .isInstanceOf(InvalidRunTransitionException.class)
                    .hasMessageContaining(from.name());
        }
    }

    @Test
    void runningIsNotATransitionToItself() {
        // A resumed job isn't a status change; allowing it would also allow nonsense.
        assertThat(RUNNING.canMoveTo(RUNNING)).isFalse();
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(PENDING.isActive()).isTrue();
        assertThat(RUNNING.isActive()).isTrue();
    }

    @Test
    void aFailureReasonIsTruncatedAndARetryClearsIt() {
        ReconciliationRun run = runIn(RUNNING);
        run.fail("x".repeat(5000), Instant.now());
        assertThat(run.getFailureReason()).hasSize(1000);
        assertThat(run.getCompletedAt()).isNotNull();

        run.transitionTo(PENDING, Instant.now());
        assertThat(run.getFailureReason()).isNull();
        assertThat(run.getCompletedAt()).isNull();
    }

    /** A run walked to {@code status} along allowed transitions. */
    static ReconciliationRun runIn(RunStatus status) {
        ReconciliationRun run = ReconciliationRun.pending(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "client:k",
                "0".repeat(64),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1),
                SourceType.INTERNAL_LEDGER,
                SourceType.GATEWAY,
                "AED",
                UUID.randomUUID(),
                Instant.now());
        switch (status) {
            case PENDING -> {}
            case RUNNING -> run.transitionTo(RUNNING, Instant.now());
            case COMPLETED -> {
                run.transitionTo(RUNNING, Instant.now());
                run.transitionTo(COMPLETED, Instant.now());
            }
            case FAILED -> {
                run.transitionTo(RUNNING, Instant.now());
                run.fail("boom", Instant.now());
            }
        }
        return run;
    }
}
