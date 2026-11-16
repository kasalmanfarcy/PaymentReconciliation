package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.error.UniqueViolations;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * M6.3: the constraints that make retries safe hold even when code is wrong. Each is attacked with
 * plain SQL and must answer {@link DuplicateKeyException} (Spring's translation of SQL state
 * 23505). Code can have bugs; constraints can't be skipped.
 */
class ReconciliationConstraintsIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    static final String INSERT_MATCH = """
            INSERT INTO reconciliation_match (id, organization_id, run_id, ledger_record_id, ledger_txn_date,
                   gateway_record_id, gateway_txn_date)
            VALUES (uuid_generate_v7(), ?, ?, ?, ?, ?, ?)""";

    @Test
    void aLedgerRecordCannotBeMatchedTwiceInOneRun() {
        var f = new ReconFixture(jdbc);
        UUID run = f.pendingRun(DAY, DAY, "AED");
        UUID ledger = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID g1 = f.gateway("PAY-1", "10.00", "AED", DAY);
        UUID g2 = f.gateway("PAY-1", "10.00", "AED", DAY);
        jdbc.update(INSERT_MATCH, f.org, run, ledger, DAY, g1, DAY);

        assertThatThrownBy(() -> jdbc.update(INSERT_MATCH, f.org, run, ledger, DAY, g2, DAY))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aGatewayRecordCannotBeMatchedTwiceInOneRun() {
        var f = new ReconFixture(jdbc);
        UUID run = f.pendingRun(DAY, DAY, "AED");
        UUID l1 = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID l2 = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID gateway = f.gateway("PAY-1", "10.00", "AED", DAY);
        jdbc.update(INSERT_MATCH, f.org, run, l1, DAY, gateway, DAY);

        assertThatThrownBy(() -> jdbc.update(INSERT_MATCH, f.org, run, l2, DAY, gateway, DAY))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theSamePairMayBeMatchedByTwoDifferentRuns() {
        // Overlapping runs are self-contained snapshots: each holds its own matches.
        var f = new ReconFixture(jdbc);
        UUID ledger = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID gateway = f.gateway("PAY-1", "10.00", "AED", DAY);
        UUID first = f.pendingRun(DAY, DAY, "AED");
        jdbc.update("UPDATE reconciliation_run SET status = 'COMPLETED' WHERE id = ?", first);
        UUID second = f.pendingRun(DAY, DAY, "AED");

        jdbc.update(INSERT_MATCH, f.org, first, ledger, DAY, gateway, DAY);
        jdbc.update(INSERT_MATCH, f.org, second, ledger, DAY, gateway, DAY);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reconciliation_match WHERE ledger_record_id = ?", Long.class, ledger))
                .isEqualTo(2);
    }

    @Test
    void anIdempotencyKeyIsUniquePerOrganization() {
        var f = new ReconFixture(jdbc);
        String insert = """
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, triggered_by, status)
                VALUES (uuid_generate_v7(), ?, 'client:same', ?, ?, ?, 'AED', ?, 'COMPLETED')""";
        jdbc.update(insert, f.org, "0".repeat(64), DAY, DAY, f.user);

        assertThatThrownBy(() -> jdbc.update(insert, f.org, "1".repeat(64), DAY.plusDays(1), DAY.plusDays(1), f.user))
                .isInstanceOf(DuplicateKeyException.class);
        // Another organization may use the same key.
        var other = new ReconFixture(jdbc);
        jdbc.update(insert, other.org, "0".repeat(64), DAY, DAY, other.user);
    }

    @Test
    void onlyOneActiveRunPerOrganizationAndScope() {
        var f = new ReconFixture(jdbc);
        f.pendingRun(DAY, DAY.plusDays(9), "AED");

        assertThatThrownBy(() -> f.pendingRun(DAY, DAY.plusDays(9), "AED"))
                .isInstanceOf(DuplicateKeyException.class)
                .satisfies(e -> assertThat(UniqueViolations.isViolationOf(e, "reconciliation_run_active_scope_uk"))
                        .isTrue());
        // Another scope (currency, or period) is a different run and may be active at once.
        f.pendingRun(DAY, DAY.plusDays(9), "USD");
        f.pendingRun(DAY, DAY.plusDays(8), "AED");
    }
}
