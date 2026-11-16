package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.token.AccessTokenService.AccessToken;
import com.reconflow.paymentreconflow.security.user.MembershipQueries.MembershipSummary;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a login, returned rather than thrown. A failed login must <em>commit</em> its
 * failure count (and audit row): an exception thrown out of the transaction would roll both back,
 * brute-force protection would silently stop working, and every test that only checks for 401
 * would still pass. The controller turns {@link Failure} into the 401.
 */
public sealed interface LoginResult {

    record Success(
            UUID userId, AccessToken accessToken, @Nullable String refreshToken, List<MembershipSummary> memberships)
            implements LoginResult {}

    /** Deliberately carries no reason: every failure must look the same to the client. */
    record Failure() implements LoginResult {}
}
