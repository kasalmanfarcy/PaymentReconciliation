package com.reconflow.paymentreconflow.ingestion.domain;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * {@code 409 duplicate-file} with {@code existingBatchId} (ADR 0005, api-design.md §3): the same
 * bytes were already imported for this organization and source type. A client retrying a timed-out
 * upload gets back exactly the batch it was trying to create.
 */
public class DuplicateFileException extends ApiException {

    /** The unique index that makes this a guarantee rather than a best effort. */
    public static final String CONSTRAINT = "import_batch_content_uk";

    public DuplicateFileException(UUID existingBatchId) {
        super(
                HttpStatus.CONFLICT,
                ProblemCodes.DUPLICATE_FILE,
                "This file was already imported as batch " + existingBatchId + ".",
                Map.of("existingBatchId", existingBatchId));
    }
}
