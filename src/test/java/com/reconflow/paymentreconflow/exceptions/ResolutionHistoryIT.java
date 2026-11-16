package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M7.17: resolve, reopen, resolve differently, and nothing is lost. The case shows only the
 * current resolution (V1's CHECK), the history shows both, with codes, notes and actors.
 */
class ResolutionHistoryIT extends AbstractExceptionApiIT {

    @Test
    @SuppressWarnings("unchecked")
    void aReopenKeepsTheEarlierResolutionVisible() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");

        assertThat(resolve(f, id, f.investigator(), "\"0\"", "GATEWAY_FEE", null)
                        .getStatus())
                .isEqualTo(200);
        assertThat(reopen(f, id, f.admin(), "\"1\"", "Fee schedule says 25, not 50")
                        .getStatus())
                .isEqualTo(200);
        assertThat(resolve(f, id, f.admin(), "\"2\"", "DATA_ENTRY_ERROR", "Ledger amount corrected upstream")
                        .getStatus())
                .isEqualTo(200);

        Map<String, Object> detail = json(detail(f, id, f.viewer()));
        List<Map<String, Object>> history = (List<Map<String, Object>>) detail.get("history");
        assertThat(history).extracting(h -> h.get("to")).containsExactly("OPEN", "RESOLVED", "OPEN", "RESOLVED");

        // The first resolution, by the investigator.
        assertThat(history.get(1))
                .containsEntry("resolutionCode", "GATEWAY_FEE")
                .containsEntry("resolutionNote", null);
        assertThat((Map<String, Object>) history.get(1).get("actor"))
                .containsEntry("id", f.investigator().toString());
        // The reopen row carries the resolution it undid, and why.
        assertThat(history.get(2)).containsEntry("from", "RESOLVED").containsEntry("resolutionCode", "GATEWAY_FEE");
        assertThat((String) history.get(2).get("reason")).contains("25, not 50");
        assertThat((Map<String, Object>) history.get(2).get("actor"))
                .containsEntry("id", f.admin().toString());
        // The second resolution, by the admin.
        assertThat(history.get(3))
                .containsEntry("resolutionCode", "DATA_ENTRY_ERROR")
                .containsEntry("resolutionNote", "Ledger amount corrected upstream");
        assertThat((Map<String, Object>) history.get(3).get("actor"))
                .containsEntry("id", f.admin().toString());

        // The case itself shows only the current resolution.
        assertThat((Map<String, Object>) detail.get("resolution"))
                .containsEntry("code", "DATA_ENTRY_ERROR")
                .containsEntry("note", "Ledger amount corrected upstream");
        assertThat(detail).containsEntry("version", 3);
        assertThat(f.auditRows(id, "EXCEPTION_RESOLVED")).isEqualTo(2);
        assertThat(f.auditRows(id, "EXCEPTION_REOPENED")).isOne();
    }
}
