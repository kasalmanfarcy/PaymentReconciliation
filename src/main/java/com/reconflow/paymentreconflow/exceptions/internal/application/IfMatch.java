package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads the version a client saw from {@code If-Match} (ADR 0007, api-design.md §6).
 *
 * <ul>
 *   <li>Missing → {@code 428 precondition-required}. Optional locking is decoration: clients would
 *       simply never send it.</li>
 *   <li>Exactly one strong ETag, {@code "7"}, is accepted. {@code *} ("any version") would defeat
 *       the purpose; a weak {@code W/"7"} never matches under the strong comparison If-Match uses
 *       (RFC 9110 §13.1.1); a list or an unquoted value is malformed. Each is {@code 400}.</li>
 * </ul>
 */
public final class IfMatch {

    private static final Pattern STRONG = Pattern.compile("^\\s*\"(\\d{1,18})\"\\s*$");

    private IfMatch() {}

    public static long parseRequired(@Nullable String header) {
        if (header == null || header.isBlank()) {
            throw new PreconditionRequiredException();
        }
        Matcher m = STRONG.matcher(header);
        if (!m.matches()) {
            throw new ValidationFailedException(
                    "If-Match",
                    "invalid-if-match",
                    "If-Match must be the case's ETag exactly as received, e.g. \"3\" (no W/, no *).");
        }
        return Long.parseLong(m.group(1));
    }

    /** {@code 428 precondition-required}: a versioned update without If-Match. */
    public static final class PreconditionRequiredException extends ApiException {

        PreconditionRequiredException() {
            super(
                    HttpStatus.PRECONDITION_REQUIRED,
                    ProblemCodes.PRECONDITION_REQUIRED,
                    "This update requires If-Match with the case's current ETag; read the case first.",
                    Map.of());
        }
    }
}
