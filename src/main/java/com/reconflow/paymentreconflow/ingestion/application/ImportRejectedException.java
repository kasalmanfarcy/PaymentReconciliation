package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An upload refused synchronously, before anything is stored (requirements.md §7.4): no batch, no
 * job, no object, no audit event. The codes are api-design.md §3.1's.
 */
public class ImportRejectedException extends ApiException {

    private ImportRejectedException(HttpStatus status, String code, String detail, Map<String, Object> properties) {
        super(status, code, detail, properties);
    }

    static ImportRejectedException emptyFile(String detail) {
        return new ImportRejectedException(HttpStatus.BAD_REQUEST, ProblemCodes.EMPTY_FILE, detail, Map.of());
    }

    static ImportRejectedException missingColumns(List<String> missing) {
        return new ImportRejectedException(
                HttpStatus.BAD_REQUEST,
                ProblemCodes.MISSING_COLUMNS,
                "Required columns are missing: " + String.join(", ", missing) + ".",
                Map.of("missingColumns", missing));
    }

    static ImportRejectedException duplicateColumns(List<String> repeated) {
        return new ImportRejectedException(
                HttpStatus.BAD_REQUEST,
                ProblemCodes.DUPLICATE_COLUMNS,
                "These header names appear more than once: " + String.join(", ", repeated) + ".",
                Map.of("duplicateColumns", repeated));
    }

    static ImportRejectedException invalidEncoding() {
        return new ImportRejectedException(
                HttpStatus.BAD_REQUEST,
                ProblemCodes.INVALID_ENCODING,
                "The header row is not valid UTF-8. Save the file as UTF-8 (CSV UTF-8 in Excel) and upload it again.",
                Map.of());
    }

    static ImportRejectedException tooLarge(long maxBytes) {
        return new ImportRejectedException(
                HttpStatus.CONTENT_TOO_LARGE,
                ProblemCodes.FILE_TOO_LARGE,
                "The file is larger than " + (maxBytes / (1024 * 1024)) + " MB.",
                Map.of());
    }

    static ImportRejectedException unsupportedMediaType() {
        return new ImportRejectedException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ProblemCodes.UNSUPPORTED_MEDIA_TYPE,
                "Upload a .csv file (text/csv, text/plain, application/vnd.ms-excel or application/octet-stream).",
                Map.of());
    }
}
