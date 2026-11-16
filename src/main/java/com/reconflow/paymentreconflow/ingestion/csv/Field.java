package com.reconflow.paymentreconflow.ingestion.csv;

/** The canonical fields the importer understands, whatever each source calls them. */
public enum Field {
    REFERENCE,
    AMOUNT,
    CURRENCY,
    STATUS,
    TRANSACTION_AT,
    TRANSACTION_DATE,
    TRANSACTION_TYPE
}
