package com.reconflow.paymentreconflow.shared.storage;

/** The object a batch points at isn't in storage. Retrying won't make it appear: permanent. */
public class StoredFileMissingException extends RuntimeException {

    public StoredFileMissingException(String key) {
        super("No stored object for key " + key);
    }
}
