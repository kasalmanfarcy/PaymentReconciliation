package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** M5.9 / M5.23: the same file twice follows ADR 0005. */
class DuplicateImportIT extends AbstractImportIT {

    @Test
    void theSecondUploadOfTheSameFileIsRejectedWithTheFirstBatchId() throws Exception {
        Tenant t = newTenant();
        UUID first = uploadOk(t, "gateway-basic.csv", sample("gateway-basic.csv"), "GATEWAY");

        MockHttpServletResponse second = upload(t, "renamed.csv", sample("gateway-basic.csv"), "GATEWAY");

        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(second.getContentType()).isEqualTo("application/problem+json");
        Map<String, Object> problem = json(second);
        assertThat(problem)
                .containsEntry("type", "https://reconflow.dev/problems/duplicate-file")
                .containsEntry("code", "duplicate-file")
                .containsEntry("status", 409)
                .containsEntry("existingBatchId", first.toString())
                .containsEntry("requestId", second.getHeader("X-Request-Id"));
        assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", t.org()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", t.org()))
                .isEqualTo(1);
        assertThat(objects(t)).isEqualTo(1);
    }

    protected long objects(Tenant t) {
        long[] n = {0};
        storage.forEach(t.org() + "/imports/", o -> n[0]++);
        return n[0];
    }
}
