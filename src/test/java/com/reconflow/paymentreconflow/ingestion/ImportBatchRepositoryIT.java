package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M5.1: the entity matches the schema (ddl-auto=validate) and round-trips, CHAR(64) included. */
class ImportBatchRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    ImportBatchRepository batches;

    @Test
    void savesAndReloadsABatchScopedToItsOrganization() {
        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        UUID id = Ids.newId();
        String hash = "a".repeat(64);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        batches.save(ImportBatch.received(
                id,
                org,
                SourceType.GATEWAY,
                "gateway.csv",
                479,
                hash,
                org + "/imports/" + id + "/original.csv",
                user,
                now));

        ImportBatch loaded = batches.findByIdAndOrganizationId(id, org).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ImportBatchStatus.RECEIVED);
        assertThat(loaded.getSourceType()).isEqualTo(SourceType.GATEWAY);
        assertThat(loaded.getContentHash()).isEqualTo(hash);
        assertThat(loaded.getFileSizeBytes()).isEqualTo(479);
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        assertThat(loaded.getTotalRows()).isZero();
        assertThat(batches.findByOrganizationIdAndSourceTypeAndContentHash(org, SourceType.GATEWAY, hash))
                .map(ImportBatch::getId)
                .contains(id);

        // Another organization's view: not found, exactly like an unknown id.
        assertThat(batches.findByIdAndOrganizationId(id, data().newOrganization()))
                .isEmpty();
    }
}
