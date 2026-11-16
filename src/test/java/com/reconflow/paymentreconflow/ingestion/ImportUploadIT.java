package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ingestion.storage.StorageKeys;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M5.7: a valid upload answers 202 with a Location, and commits the batch, its job and its audit
 * event together, with the file already in storage. Rejections are in ImportRejectionIT (M5.26).
 */
class ImportUploadIT extends AbstractImportIT {

    @Test
    void aValidFileIsAcceptedForBackgroundProcessing() throws Exception {
        Tenant t = newTenant();

        MockHttpServletResponse res = upload(t, "gateway-basic.csv", sample("gateway-basic.csv"), "GATEWAY");

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        Map<String, Object> body = json(res);
        UUID id = UUID.fromString((String) body.get("id"));
        assertThat(res.getHeader("Location")).isEqualTo("/api/v1/imports/" + id);
        assertThat(res.getHeader("Retry-After")).isEqualTo("2");
        assertThat(body)
                .containsEntry("status", "RECEIVED")
                .containsEntry("sourceType", "GATEWAY")
                .containsEntry("fileName", "gateway-basic.csv")
                .containsEntry("sizeBytes", sample("gateway-basic.csv").length)
                .containsEntry("totalRows", 0)
                .containsEntry("storedErrors", 0)
                .containsEntry("failureReason", null)
                .containsEntry("uploadedBy", t.uploader().toString())
                .containsEntry("startedAt", null)
                .doesNotContainKeys("storageKey", "contentHash");

        Map<String, Object> batch = batchRow(id);
        assertThat(batch).containsEntry("status", "RECEIVED").containsEntry("organization_id", t.org());
        assertThat(batch.get("storage_key")).isEqualTo(StorageKeys.original(t.org(), id));
        assertThat(batch.get("content_hash").toString()).hasSize(64);

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT type, status, payload->>'batchId' AS batch FROM job WHERE organization_id = ?", t.org());
        assertThat(job)
                .containsEntry("type", "IMPORT")
                .containsEntry("status", "QUEUED")
                .containsEntry("batch", id.toString());

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action, actor_id, entity_type, request_id, payload->>'fileName' AS file FROM audit_event"
                        + " WHERE organization_id = ? AND entity_id = ?",
                t.org(),
                id);
        assertThat(audit)
                .containsEntry("action", "IMPORT_RECEIVED")
                .containsEntry("entity_type", "IMPORT_BATCH")
                .containsEntry("actor_id", t.uploader())
                .containsEntry("file", "gateway-basic.csv")
                .containsEntry("request_id", res.getHeader("X-Request-Id"));

        assertThat(storage.exists(StorageKeys.original(t.org(), id))).isTrue();
    }

    @Test
    void theFileNameIsDisplayOnlyAndNeverReachesTheStorageKey() throws Exception {
        Tenant t = newTenant();

        UUID id = uploadOk(t, "../../etc/passwd.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");

        assertThat(batchRow(id)).containsEntry("file_name", "passwd.csv");
        assertThat(batchRow(id).get("storage_key")).isEqualTo(StorageKeys.original(t.org(), id));
    }
}
