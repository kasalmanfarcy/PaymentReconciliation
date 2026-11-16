package com.reconflow.paymentreconflow.ledger;

/**
 * Where a file, and every record imported from it, came from. Matches the CHECK on
 * {@code import_batch.source_type} and {@code transaction_record.source_type}.
 *
 * <p>Lives in {@code ledger}, not {@code ingestion}: a record carries its source, and ledger may not
 * depend on ingestion (architecture.md §4), while ingestion may depend on ledger.
 */
public enum SourceType {
    INTERNAL_LEDGER,
    GATEWAY
}
