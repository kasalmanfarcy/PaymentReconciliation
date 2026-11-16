package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRunRepository;
import com.reconflow.paymentreconflow.reconciliation.domain.RunStatus;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M6.1: V5 applies, the entity matches the schema ({@code ddl-auto=validate} already passed when
 * the context started), a run survives a save and reload, and the database's UUIDv7 function
 * produces real version-7 UUIDs with a current timestamp.
 */
class ReconciliationRunRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    ReconciliationRunRepository runs;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aRunSurvivesSaveAndReload() {
        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.INVESTIGATOR).id();
        UUID id = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        tx.executeWithoutResult(s -> runs.save(ReconciliationRun.pending(
                id,
                org,
                "client:abc",
                "a".repeat(64),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                SourceType.INTERNAL_LEDGER,
                SourceType.GATEWAY,
                "AED",
                user,
                now)));

        ReconciliationRun loaded =
                tx.execute(s -> runs.findByIdAndOrganizationId(id, org).orElseThrow());
        assertThat(loaded.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(loaded.getIdempotencyKey()).isEqualTo("client:abc");
        assertThat(loaded.getRequestFingerprint()).isEqualTo("a".repeat(64));
        assertThat(loaded.getCurrency()).isEqualTo("AED");
        assertThat(loaded.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(loaded.getLastCompletedDate()).isNull();
        assertThat(loaded.summary().matchedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        // Another organization's id lookup finds nothing.
        UUID otherOrg = data().newOrganization();
        Optional<ReconciliationRun> elsewhere = tx.execute(s -> runs.findByIdAndOrganizationId(id, otherOrg));
        assertThat(elsewhere).isEmpty();
    }

    @Test
    void uuidGenerateV7ProducesVersion7WithTheCurrentTime() {
        UUID fromDatabase = jdbc.queryForObject("SELECT uuid_generate_v7()", UUID.class);
        UUID fromJava = Ids.newId();

        assertThat(fromDatabase.version()).isEqualTo(7);
        assertThat(fromJava.version()).isEqualTo(7);
        assertThat(fromDatabase.variant()).isEqualTo(fromJava.variant()).isEqualTo(2);
        // The first 48 bits are Unix milliseconds in both generators.
        long millis = fromDatabase.getMostSignificantBits() >>> 16;
        assertThat(Duration.between(Instant.ofEpochMilli(millis), Instant.now()).abs())
                .isLessThan(Duration.ofSeconds(1));
    }
}
