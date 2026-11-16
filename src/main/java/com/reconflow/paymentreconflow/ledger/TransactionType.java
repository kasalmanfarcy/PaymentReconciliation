package com.reconflow.paymentreconflow.ledger;

/** Rule N10; matches the CHECK on {@code transaction_record.transaction_type}. Blank → PAYMENT. */
public enum TransactionType {
    PAYMENT,
    REFUND,
    CHARGEBACK,
    FEE
}
