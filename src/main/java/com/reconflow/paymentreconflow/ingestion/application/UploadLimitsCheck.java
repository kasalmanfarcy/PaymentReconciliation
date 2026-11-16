package com.reconflow.paymentreconflow.ingestion.application;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * M10.3: the upload limit exists at two layers, the servlet container (multipart parsing, before the
 * controller) and the code (ImportReceiver). If they drift apart, one layer silently becomes the
 * only one, or every maximum-size upload fails because the request limit is below the file limit.
 * Refuse to start instead.
 */
@Component
class UploadLimitsCheck {

    UploadLimitsCheck(Environment environment, ImportProperties props) {
        Binder binder = Binder.get(environment);
        DataSize containerFile = binder.bind("spring.servlet.multipart.max-file-size", DataSize.class)
                .orElseThrow(() -> new IllegalStateException("spring.servlet.multipart.max-file-size is not set"));
        DataSize containerRequest = binder.bind("spring.servlet.multipart.max-request-size", DataSize.class)
                .orElseThrow(() -> new IllegalStateException("spring.servlet.multipart.max-request-size is not set"));
        check(containerFile, containerRequest, props.maxFileSize());
    }

    static void check(DataSize containerFile, DataSize containerRequest, DataSize codeFile) {
        if (!containerFile.equals(codeFile)) {
            throw new IllegalStateException("Upload limits disagree: spring.servlet.multipart.max-file-size="
                    + containerFile + " but reconflow.import.max-file-size=" + codeFile);
        }
        if (containerRequest.compareTo(containerFile) < 0) {
            throw new IllegalStateException("spring.servlet.multipart.max-request-size (" + containerRequest
                    + ") is below max-file-size (" + containerFile + "): every maximum-size upload would fail");
        }
    }
}
