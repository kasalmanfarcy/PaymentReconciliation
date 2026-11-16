package com.reconflow.paymentreconflow.ledger;

/**
 * The normalized status of a record (rule N9); matches the CHECK on
 * {@code transaction_record.status}. Only {@code SUCCESS} records take part in a run (rule P4).
 */
public enum TransactionStatus {
    SUCCESS,
    PENDING,
    FAILED,
    REVERSED
}
