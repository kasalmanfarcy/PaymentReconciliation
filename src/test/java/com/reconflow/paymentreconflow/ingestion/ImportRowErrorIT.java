package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchRepository;
import com.reconflow.paymentreconflow.ingestion.domain.ImportRowError;
import com.reconflow.paymentreconflow.ingestion.domain.ImportRowErrorRepository;
import com.reconflow.paymentreconflow.ingestion.domain.NewRowError;
import com.reconflow.paymentreconflow.ingestion.domain.RowErrorWriter;
import com.reconflow.paymentreconflow.ingestion.validation.RowErrorCode;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionTemplate;

/** M5.2: the same error twice is stored once, including a whole-row (NULL column) error. */
class ImportRowErrorIT extends AbstractIntegrationTest {

    @Autowired
    RowErrorWriter writer;

    @Autowired
    ImportRowErrorRepository errors;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aRepeatedErrorIsStoredOnceEvenWithANullColumn() {
        UUID org = data().newOrganization();
        UUID batch = newBatch(org);
        List<NewRowError> twice = List.of(
                new NewRowError(9, null, RowErrorCode.MALFORMED_ROW, "Quoted field is never closed", "PAY-409,\"60"),
                new NewRowError(2, "amount", RowErrorCode.INVALID_AMOUNT, "not a plain decimal", "PAY-402"));

        inTenant(org, () -> tx.executeWithoutResult(s -> writer.insert(org, batch, twice)));
        inTenant(org, () -> tx.executeWithoutResult(s -> writer.insert(org, batch, twice))); // a resumed chunk

        long stored = asTenant(org, () -> tx.execute(s -> errors.countByOrganizationIdAndBatchId(org, batch)));
        assertThat(stored).isEqualTo(2);
        List<ImportRowError> page = asTenant(
                org,
                () -> tx.execute(s -> errors.findByOrganizationIdAndBatchId(
                                org, batch, PageRequest.of(0, 50, Sort.by("rowNumber", "columnName")))
                        .getContent()));
        assertThat(page).extracting(ImportRowError::getRowNumber).containsExactly(2, 9);
        assertThat(page.get(1).getColumnName()).isNull();
        assertThat(page.get(1).getErrorCode()).isEqualTo("MALFORMED_ROW");
    }

    private UUID newBatch(UUID org) {
        UUID user = data().newMember(org, Role.UPLOADER).id();
        UUID id = Ids.newId();
        inTenant(
                org,
                () -> tx.executeWithoutResult(s -> batches.save(ImportBatch.received(
                        id,
                        org,
                        SourceType.INTERNAL_LEDGER,
                        "l.csv",
                        1,
                        "b".repeat(64),
                        "k/" + id,
                        user,
                        Instant.now()))));
        return id;
    }
}
