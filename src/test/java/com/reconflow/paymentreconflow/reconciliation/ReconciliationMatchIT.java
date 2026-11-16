package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.reconciliation.domain.MatchType;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationMatch;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationMatchRepository;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** M6.2: a match written by SQL (the only way matches are written) reads back through the entity. */
class ReconciliationMatchIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    ReconciliationMatchRepository matches;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aMatchInsertedBySqlReadsBack() {
        var f = new ReconFixture(jdbc);
        UUID ledger = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID gateway = f.gateway("PAY-1", "10.0", "AED", DAY.plusDays(1));
        UUID run = f.pendingRun(DAY, DAY.plusDays(1), "AED");
        UUID id = jdbc.queryForObject("""
                INSERT INTO reconciliation_match (id, organization_id, run_id, ledger_record_id, ledger_txn_date,
                       gateway_record_id, gateway_txn_date)
                VALUES (uuid_generate_v7(), ?, ?, ?, ?, ?, ?) RETURNING id""", UUID.class, f.org, run, ledger, DAY, gateway, DAY.plusDays(1));

        ReconciliationMatch m = asTenant(
                f.org,
                () -> tx.execute(
                        s -> matches.findByIdAndOrganizationId(id, f.org).orElseThrow()));

        assertThat(m.getRunId()).isEqualTo(run);
        assertThat(m.getLedgerRecordId()).isEqualTo(ledger);
        assertThat(m.getGatewayRecordId()).isEqualTo(gateway);
        assertThat(m.getGatewayTxnDate()).isEqualTo(DAY.plusDays(1));
        assertThat(m.getMatchType()).isEqualTo(MatchType.EXACT);
        assertThat(m.getAmountDifference()).isEqualByComparingTo(BigDecimal.ZERO);
        List<ReconciliationMatch> forLedger = asTenant(
                f.org,
                () -> tx.execute(s -> matches.findByOrganizationIdAndRunIdAndLedgerRecordId(f.org, run, ledger)));
        assertThat(forLedger).hasSize(1);
    }
}
