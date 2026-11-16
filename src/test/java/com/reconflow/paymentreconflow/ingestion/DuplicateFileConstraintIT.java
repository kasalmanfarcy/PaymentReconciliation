package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reconflow.paymentreconflow.ingestion.domain.DuplicateFileException;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.error.UniqueViolations;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * M5.4: import_batch_content_uk rejects a second batch with the same bytes, organization and
 * source type, and the violation is recognisable by constraint name. Same bytes as the other
 * source type is a different import (ADR 0005).
 */
class DuplicateFileConstraintIT extends AbstractIntegrationTest {

    @Autowired
    ImportBatchRepository batches;

    @Test
    void theSameContentTwiceViolatesTheNamedConstraint() {
        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        String hash = "c".repeat(64);
        batches.saveAndFlush(batch(org, user, SourceType.GATEWAY, hash));

        Throwable failure = catchThrowable(() -> batches.saveAndFlush(batch(org, user, SourceType.GATEWAY, hash)));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(UniqueViolations.isViolationOf(failure, DuplicateFileException.CONSTRAINT))
                .isTrue();
        // Same bytes uploaded as the other source type: a different import, accepted.
        batches.saveAndFlush(batch(org, user, SourceType.INTERNAL_LEDGER, hash));
        // ...and as the same source type in another organization.
        UUID other = data().newOrganization();
        batches.saveAndFlush(batch(other, data().newMember(other, Role.UPLOADER).id(), SourceType.GATEWAY, hash));
    }

    @Test
    void anUnrelatedFailureIsNotMistakenForADuplicate() {
        assertThat(UniqueViolations.isViolationOf(new IllegalStateException("x"), DuplicateFileException.CONSTRAINT))
                .isFalse();
    }

    private static ImportBatch batch(UUID org, UUID user, SourceType type, String hash) {
        UUID id = Ids.newId();
        return ImportBatch.received(id, org, type, "f.csv", 1, hash, org + "/imports/" + id, user, Instant.now());
    }
}
