package com.reconflow.paymentreconflow.shared.error;

/**
 * Every {@code code} the API can return, mirroring the catalogue in docs/api-design.md §3.1.
 * Clients branch on these values, so they never change once published; titles and details may.
 * Add a constant here and a row in the catalogue together.
 */
public final class ProblemCodes {

    private ProblemCodes() {}

    // 400
    public static final String VALIDATION_FAILED = "validation-failed";
    public static final String ORGANIZATION_REQUIRED = "organization-required";
    public static final String MISSING_COLUMNS = "missing-columns";
    public static final String DUPLICATE_COLUMNS = "duplicate-columns";
    public static final String EMPTY_FILE = "empty-file";
    public static final String INVALID_ENCODING = "invalid-encoding";
    public static final String PASSWORD_POLICY = "password-policy";
    // 401
    public static final String UNAUTHENTICATED = "unauthenticated";
    public static final String INVALID_CREDENTIALS = "invalid-credentials";
    // 403
    public static final String FORBIDDEN = "forbidden";
    public static final String PASSWORD_CHANGE_REQUIRED = "password-change-required";
    // 404
    public static final String NOT_FOUND = "not-found";
    public static final String ORGANIZATION_NOT_FOUND = "organization-not-found";
    // 405, 406
    public static final String METHOD_NOT_ALLOWED = "method-not-allowed";
    public static final String NOT_ACCEPTABLE = "not-acceptable";
    // 409
    public static final String DUPLICATE_FILE = "duplicate-file";
    public static final String IMPORT_IN_PROGRESS = "import-in-progress";
    public static final String STALE_VERSION = "stale-version";
    public static final String INVALID_TRANSITION = "invalid-transition";
    public static final String ACTIVE_CASE_EXISTS = "active-case-exists";
    public static final String LAST_ORG_ADMIN = "last-org-admin";
    public static final String ALREADY_A_MEMBER = "already-a-member";
    public static final String RUN_IN_PROGRESS = "run-in-progress";
    public static final String RUN_NOT_COMPLETED = "run-not-completed";
    // 413, 415
    public static final String FILE_TOO_LARGE = "file-too-large";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported-media-type";
    // 422
    public static final String IDEMPOTENCY_KEY_REUSED = "idempotency-key-reused";
    public static final String INVALID_ASSIGNEE = "invalid-assignee";
    // 428, 429
    public static final String PRECONDITION_REQUIRED = "precondition-required";
    public static final String RATE_LIMITED = "rate-limited";
    // 500
    public static final String INTERNAL_ERROR = "internal-error";

    /**
     * The code for an error Spring raised itself (no {@link ApiException}), chosen by status.
     * A status with no catalogue entry gets {@code http-<status>}; clients fall back on the status.
     */
    public static String forStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> FILE_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 428 -> PRECONDITION_REQUIRED;
            case 429 -> RATE_LIMITED;
            default -> status >= 500 ? INTERNAL_ERROR : "http-" + status;
        };
    }
}
