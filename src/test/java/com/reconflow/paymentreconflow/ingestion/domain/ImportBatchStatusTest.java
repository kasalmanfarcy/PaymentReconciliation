package com.reconflow.paymentreconflow.ingestion.domain;

import static com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus.COMPLETED;
import static com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus.COMPLETED_WITH_ERRORS;
import static com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus.FAILED;
import static com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus.PROCESSING;
import static com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus.RECEIVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** M5.15: every pair of statuses (25 cases); allowed ones succeed, every other one throws. */
class ImportBatchStatusTest {

    /** reconciliation-rules.md §7.1, written out independently of the enum's own table. */
    static final Set<String> ALLOWED = Set.of(
            "RECEIVED->PROCESSING",
            "RECEIVED->FAILED",
            "PROCESSING->COMPLETED",
            "PROCESSING->COMPLETED_WITH_ERRORS",
            "PROCESSING->FAILED",
            "FAILED->RECEIVED");

    static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    static Stream<Arguments> everyPair() {
        return Stream.of(ImportBatchStatus.values())
                .flatMap(from -> Stream.of(ImportBatchStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void onlyTheDocumentedTransitionsAreAllowed(ImportBatchStatus from, ImportBatchStatus to) {
        ImportBatch batch = batchIn(from);
        boolean allowed = ALLOWED.contains(from + "->" + to);

        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        if (allowed) {
            batch.transitionTo(to, NOW);
            assertThat(batch.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> batch.transitionTo(to, NOW))
                    .isInstanceOfSatisfying(InvalidTransitionException.class, e -> {
                        assertThat(e.code()).isEqualTo("invalid-transition");
                        assertThat(e.properties()).containsEntry("currentStatus", from.name());
                    });
            assertThat(batch.getStatus()).isEqualTo(from);
        }
    }

    @ParameterizedTest
    @MethodSource("terminal")
    void terminalStatusesAreTheThreeEnds(ImportBatchStatus status, boolean terminal) {
        assertThat(status.isTerminal()).isEqualTo(terminal);
    }

    static Stream<Arguments> terminal() {
        return Stream.of(
                Arguments.of(RECEIVED, false),
                Arguments.of(PROCESSING, false),
                Arguments.of(COMPLETED, true),
                Arguments.of(COMPLETED_WITH_ERRORS, true),
                Arguments.of(FAILED, true));
    }

    /** Walks a new batch along allowed transitions to the wanted status. */
    static ImportBatch batchIn(ImportBatchStatus status) {
        ImportBatch b = ImportBatch.received(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SourceType.GATEWAY,
                "f.csv",
                1,
                "a".repeat(64),
                "k",
                UUID.randomUUID(),
                NOW);
        switch (status) {
            case RECEIVED -> {}
            case PROCESSING -> b.transitionTo(PROCESSING, NOW);
            case COMPLETED, COMPLETED_WITH_ERRORS -> {
                b.transitionTo(PROCESSING, NOW);
                b.transitionTo(status, NOW);
            }
            case FAILED -> b.transitionTo(FAILED, NOW);
        }
        return b;
    }
}
