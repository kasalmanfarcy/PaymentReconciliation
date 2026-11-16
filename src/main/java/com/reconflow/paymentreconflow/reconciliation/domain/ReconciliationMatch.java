package com.reconflow.paymentreconflow.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One matched ledger/gateway pair. <strong>Read-only</strong>: matches are written set-based by the
 * engine's {@code INSERT ... SELECT} (M6.5), never through JPA ({@code saveAll} of millions of
 * entities would defeat the design). The entity exists so {@code ddl-auto=validate} checks the
 * table, and for single lookups; the paginated endpoint reads a joined projection with SQL.
 *
 * <p>No {@code @ManyToOne TransactionRecord}: that table is partitioned (composite key with the
 * date) and belongs to {@code ledger}. Each record id travels with its date, which also lets every
 * join prune to one partition.
 */
@Entity
@Immutable
@Table(name = "reconciliation_match")
public class ReconciliationMatch {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "ledger_record_id", nullable = false)
    private UUID ledgerRecordId;

    @Column(name = "ledger_txn_date", nullable = false)
    private LocalDate ledgerTxnDate;

    @Column(name = "gateway_record_id", nullable = false)
    private UUID gatewayRecordId;

    @Column(name = "gateway_txn_date", nullable = false)
    private LocalDate gatewayTxnDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    private MatchType matchType;

    /** Always 0 for EXACT; exists for fee-aware matching in v2. */
    @Column(name = "amount_difference", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountDifference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationMatch() {} // JPA; never constructed by application code

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getLedgerRecordId() {
        return ledgerRecordId;
    }

    public LocalDate getLedgerTxnDate() {
        return ledgerTxnDate;
    }

    public UUID getGatewayRecordId() {
        return gatewayRecordId;
    }

    public LocalDate getGatewayTxnDate() {
        return gatewayTxnDate;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public BigDecimal getAmountDifference() {
        return amountDifference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
