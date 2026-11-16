package com.reconflow.paymentreconflow.security.token;

import com.reconflow.paymentreconflow.security.token.AccessTokenService.AccessToken;
import com.reconflow.paymentreconflow.security.token.RefreshTokenService.RotationResult;
import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The refresh and logout flows on top of {@link RefreshTokenService} (authentication.plan §4.3–4.4). */
@Service
public class RefreshService {

    /** A new access token and the rotated refresh token, or nothing (401). */
    public record Refreshed(AccessToken accessToken, String refreshToken) {}

    private final RefreshTokenService refreshTokens;
    private final AccessTokenService accessTokens;
    private final AppUserRepository users;
    private final TransactionTemplate tx;

    RefreshService(
            RefreshTokenService refreshTokens,
            AccessTokenService accessTokens,
            AppUserRepository users,
            TransactionTemplate tx) {
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.users = users;
        this.tx = tx;
    }

    public Optional<Refreshed> refresh(String rawToken) {
        if (!(refreshTokens.rotate(rawToken) instanceof RotationResult.Rotated rotated)) {
            return Optional.empty();
        }
        // The user may have been disabled since the family started; disabling also revokes the
        // family, but checking here closes the window between the two.
        AppUser user = users.findById(rotated.userId()).orElseThrow();
        if (!user.isActive()) {
            return Optional.empty();
        }
        return Optional.of(new Refreshed(accessTokens.issue(user), rotated.newToken()));
    }

    /**
     * Revokes the presented token's family. Idempotent: an unknown, expired or already revoked
     * token is simply ignored, and the caller always answers 204 and clears the cookie.
     */
    public void logout(String rawToken) {
        refreshTokens.find(rawToken).ifPresent(row -> {
            if (row.revokedAt() == null) {
                tx.executeWithoutResult(status -> refreshTokens.revokeFamily(row.familyId(), RevocationReason.LOGOUT));
            }
        });
    }
}
