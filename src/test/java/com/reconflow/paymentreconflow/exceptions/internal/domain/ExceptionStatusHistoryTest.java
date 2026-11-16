package com.reconflow.paymentreconflow.exceptions.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M7.4: which history row carries which resolution, so a reopen never loses one. */
class ExceptionStatusHistoryTest {

    final UUID actor = UUID.randomUUID();
    final ExceptionCase c = ExceptionCase.forTest(UUID.randomUUID(), UUID.randomUUID(), ExceptionStatus.OPEN, 0);

    @Test
    void aStatusChangeCarriesNoResolution() {
        var row = ExceptionStatusHistory.statusChange(
                c, ExceptionStatus.IN_REVIEW, ExceptionStatus.OPEN, "waiting for the gateway", actor);

        assertThat(row.getFromStatus()).isEqualTo(ExceptionStatus.IN_REVIEW);
        assertThat(row.getToStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(row.getReason()).isEqualTo("waiting for the gateway");
        assertThat(row.getResolutionCode()).isNull();
        assertThat(row.getResolutionNote()).isNull();
        assertThat(row.getActorId()).isEqualTo(actor);
        assertThat(row.getExceptionId()).isEqualTo(c.getId());
    }

    @Test
    void theResolveRowCarriesTheNewResolution() {
        var row = ExceptionStatusHistory.resolved(c, ExceptionStatus.OPEN, ResolutionCode.OTHER, "explained", actor);

        assertThat(row.getToStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(row.getResolutionCode()).isEqualTo(ResolutionCode.OTHER);
        assertThat(row.getResolutionNote()).isEqualTo("explained");
        assertThat(row.getReason()).isNull();
    }

    @Test
    void theReopenRowCarriesThePreviousResolutionAndTheReason() {
        var row = ExceptionStatusHistory.reopened(c, "fee is 25, not 50", ResolutionCode.GATEWAY_FEE, null, actor);

        assertThat(row.getFromStatus()).isEqualTo(ExceptionStatus.RESOLVED);
        assertThat(row.getToStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(row.getReason()).isEqualTo("fee is 25, not 50");
        assertThat(row.getResolutionCode()).isEqualTo(ResolutionCode.GATEWAY_FEE);
        assertThat(row.getResolutionNote()).isNull();
    }

    @Test
    void ofTheResolutionCodesExactlyFiveNeedANote() {
        // reconciliation-rules.md §5.3
        assertThat(java.util.Arrays.stream(ResolutionCode.values())
                        .filter(ResolutionCode::noteRequired)
                        .map(Enum::name))
                .containsExactlyInAnyOrder(
                        "DATA_ENTRY_ERROR", "DUPLICATE_CONFIRMED", "WRITTEN_OFF", "FALSE_POSITIVE", "OTHER");
    }
}
