package com.reconflow.paymentreconflow.shared.context;

/**
 * Tenant-scoped code ran with no organization bound: a programming error, never a client error.
 * Typically work handed to another thread ({@code @Async}, a parallel stream), which does not see
 * the scoped value. Failing closed is the point; never "fix" it by copying the ID into a field.
 */
public class NoTenantException extends IllegalStateException {

    public NoTenantException(String message) {
        super(message);
    }
}
