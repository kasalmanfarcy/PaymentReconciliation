package com.reconflow.paymentreconflow.exceptions.internal.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** The production {@link ExceptionWriteHook}: does nothing. */
@Component
class NoopExceptionWriteHook implements ExceptionWriteHook {

    @Override
    public void afterVersionCheck(UUID caseId) {}

    @Override
    public void beforeCommit(UUID caseId) {}
}
