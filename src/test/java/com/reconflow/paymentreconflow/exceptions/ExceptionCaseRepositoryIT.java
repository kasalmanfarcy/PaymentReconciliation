package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCase;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCaseRepository;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M7.1: the entity maps the row DiscrepancyRecorder wrote (ddl-auto=validate already passed when
 * the context started), and lookups are tenant-scoped.
 */
class ExceptionCaseRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    JobRunner jobs;

    @Autowired
    ExceptionCaseRepository cases;

    @Autowired
    TransactionTemplate tx;

    @Test
    void loadsACaseTheRecorderCreated() {
        var f = new ExceptionFixture(jdbc, jobs);
        UUID id = f.amountMismatch("PAY-103");

        ExceptionCase c =
                tx.execute(s -> cases.findByIdAndOrganizationId(id, f.org).orElseThrow());

        assertThat(c.getType()).isEqualTo(ExceptionType.AMOUNT_MISMATCH);
        assertThat(c.getStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(c.getDiscrepancyKey()).isEqualTo("AMOUNT_MISMATCH:PAY-103:PAYMENT:AED");
        assertThat(c.getCurrency()).isEqualTo("AED");
        assertThat(c.getAmount()).isEqualByComparingTo("750.00");
        assertThat(c.getAmountDifference()).isEqualByComparingTo("-50.00");
        assertThat(c.getVersion()).isZero();
        assertThat(c.getAssignedTo()).isNull();
        assertThat(c.getResolutionCode()).isNull();
        assertThat(c.getCreatedAt()).isNotNull();
    }

    @Test
    void anotherOrganizationsCaseIsNotFound() {
        var f = new ExceptionFixture(jdbc, jobs);
        UUID id = f.amountMismatch("PAY-103");
        UUID otherOrg = data().newOrganization();

        String key = "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED";
        Optional<ExceptionCase> foreign = tx.execute(s -> cases.findByIdAndOrganizationId(id, otherOrg));
        Optional<UUID> foreignActive = tx.execute(s -> cases.findActiveIdByOrganizationIdAndKey(otherOrg, key));
        Optional<UUID> ownActive = tx.execute(s -> cases.findActiveIdByOrganizationIdAndKey(f.org, key));

        assertThat(foreign).isEmpty();
        assertThat(foreignActive).isEmpty();
        assertThat(ownActive).contains(id);
    }
}
