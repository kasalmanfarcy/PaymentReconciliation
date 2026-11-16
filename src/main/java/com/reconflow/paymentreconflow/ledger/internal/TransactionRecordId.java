package com.reconflow.paymentreconflow.ledger.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The composite primary key {@code (id, transaction_date)}: PostgreSQL requires the partition key
 * in every unique constraint of a partitioned table, the primary key included. Foreign keys into
 * the table carry the date for the same reason, which also lets their joins prune to one partition.
 */
@Embeddable
public record TransactionRecordId(
        @Column(name = "id") UUID id,
        @Column(name = "transaction_date") LocalDate transactionDate) implements Serializable {}
