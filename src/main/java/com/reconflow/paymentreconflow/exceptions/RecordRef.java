package com.reconflow.paymentreconflow.exceptions;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A transaction record a case links to. The date travels with the id because
 * {@code transaction_record} is partitioned by it: the foreign key is {@code (id, transaction_date)}.
 * No source type here: this module doesn't depend on {@code ledger} (architecture.md §4), and the
 * link table has no use for it.
 */
public record RecordRef(UUID id, LocalDate transactionDate) {}
