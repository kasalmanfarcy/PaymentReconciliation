package com.reconflow.paymentreconflow.exceptions.internal.domain;

import org.jspecify.annotations.Nullable;

/**
 * What a user supplied with a transition. Raw strings: {@link ReasonRule} validates them, so an
 * unknown resolution code is a 400 with a field error, not a JSON parse failure.
 *
 * @param reason why (back to open, reopen)
 * @param resolutionCode a {@link ResolutionCode} name (resolve)
 * @param note the resolution note (resolve); required for some codes
 */
public record TransitionInput(
        @Nullable String reason,
        @Nullable String resolutionCode,
        @Nullable String note) {

    public static final TransitionInput NONE = new TransitionInput(null, null, null);

    public static TransitionInput reason(@Nullable String reason) {
        return new TransitionInput(reason, null, null);
    }

    public static TransitionInput resolution(@Nullable String code, @Nullable String note) {
        return new TransitionInput(null, code, note);
    }
}
