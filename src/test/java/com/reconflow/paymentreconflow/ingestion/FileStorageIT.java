package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.ingestion.storage.FileStorage;
import com.reconflow.paymentreconflow.ingestion.storage.OrphanFileSweeper;
import com.reconflow.paymentreconflow.ingestion.storage.StorageKeys;
import com.reconflow.paymentreconflow.ingestion.storage.StoredFileMissingException;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M5.8: put / download / delete against real MinIO, and the orphan sweeper's rule. */
class FileStorageIT extends AbstractIntegrationTest {

    @Autowired
    FileStorage storage;

    @Autowired
    OrphanFileSweeper sweeper;

    @Test
    void putsDownloadsAndDeletesAnObject() throws Exception {
        String key = StorageKeys.original(Ids.newId(), Ids.newId());
        byte[] content = "reference,amount\nPAY-1,1.00\n".getBytes(StandardCharsets.UTF_8);

        storage.put(key, new ByteArrayInputStream(content), content.length, "text/csv");
        assertThat(storage.exists(key)).isTrue();

        Path local = Files.createTempFile("download-", ".csv");
        try {
            storage.download(key, local);
            assertThat(Files.readAllBytes(local)).isEqualTo(content);
        } finally {
            Files.deleteIfExists(local);
        }

        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
        assertThatThrownBy(() -> storage.download(key, Files.createTempFile("missing-", ".csv")))
                .isInstanceOf(StoredFileMissingException.class);
    }

    @Test
    void theSweeperDeletesOldUnreferencedFilesAndKeepsReferencedOnes() {
        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        UUID batch = data().newImportBatch(org, user, "GATEWAY", "COMPLETED");
        String referenced = StorageKeys.original(org, batch); // TestData uses the same key layout
        String orphan = StorageKeys.original(org, Ids.newId());
        put(referenced);
        put(orphan);

        // Nothing is old enough yet: an upload in flight is never touched.
        sweeper.sweepOlderThan(Instant.now().minusSeconds(3600));
        assertThat(storage.exists(orphan)).isTrue();

        // A cutoff in the future makes both "older than the threshold".
        int deleted = sweeper.sweepOlderThan(Instant.now().plusSeconds(60));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(storage.exists(orphan)).isFalse();
        assertThat(storage.exists(referenced)).isTrue();
    }

    private void put(String key) {
        byte[] b = "x".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(b), b.length, "text/csv");
    }
}
