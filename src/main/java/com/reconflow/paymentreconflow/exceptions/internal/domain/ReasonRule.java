package com.reconflow.paymentreconflow.exceptions.internal.domain;

import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a transition requires from the user besides permission. Violations are {@code 400
 * validation-failed} with a field error. The code-dependent "note required" rule lives here and not
 * in Bean Validation, which can't express it without a class-level custom constraint.
 */
public enum ReasonRule {
    /** Nothing required; a reason sent anyway is ignored. */
    NONE,
    /** {@code reason}: not blank, at most 1,000 characters. */
    REQUIRED,
    /** {@code resolutionCode}: a known code; {@code note}: required if the code needs one, at most 4,000. */
    RESOLUTION;

    public static final int MAX_REASON = 1000;
    public static final int MAX_NOTE = 4000;

    /** @return the parsed code for {@link #RESOLUTION}, else null */
    @Nullable
    ResolutionCode validate(TransitionInput in) {
        switch (this) {
            case NONE -> {
                return null;
            }
            case REQUIRED -> {
                if (isBlank(in.reason())) {
                    throw new ValidationFailedException("reason", "required", "A reason is required for this change.");
                }
                if (in.reason().length() > MAX_REASON) {
                    throw new ValidationFailedException(
                            "reason", "size", "The reason must be at most " + MAX_REASON + " characters.");
                }
                return null;
            }
            case RESOLUTION -> {
                if (isBlank(in.resolutionCode())) {
                    throw new ValidationFailedException(
                            "resolutionCode", "required", "A resolution code is required to resolve a case.");
                }
                ResolutionCode code = parse(in.resolutionCode());
                if (code.noteRequired() && isBlank(in.note())) {
                    throw new ValidationFailedException(
                            "note", "required-for-code", "Resolution code " + code + " requires a note.");
                }
                if (in.note() != null && in.note().length() > MAX_NOTE) {
                    throw new ValidationFailedException(
                            "note", "size", "The note must be at most " + MAX_NOTE + " characters.");
                }
                return code;
            }
        }
        throw new IllegalStateException(name());
    }

    private static ResolutionCode parse(String raw) {
        try {
            return ResolutionCode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException(
                    "resolutionCode",
                    "unknown-resolution-code",
                    "Unknown resolution code; see reconciliation-rules.md §5.3.");
        }
    }

    static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
