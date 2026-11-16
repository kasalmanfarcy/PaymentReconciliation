package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.token.AccessTokenService;
import com.reconflow.paymentreconflow.security.token.AccessTokenService.AccessToken;
import com.reconflow.paymentreconflow.security.token.RefreshTokenService;
import com.reconflow.paymentreconflow.security.token.RevocationReason;
import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.MembershipQueries;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Login (authentication.plan §4.1). The order of the steps is the security argument:
 *
 * <ol>
 *   <li>Look the user up by lower(email).</li>
 *   <li>Run bcrypt, against a dummy hash at the same cost when the email is unknown, so response
 *       time doesn't reveal which emails have accounts.</li>
 *   <li>Only then check locks and status. Checking them first would answer a locked account in
 *       ~1 ms instead of ~200 ms: exactly the timing signal the dummy hash removes.</li>
 *   <li>Record the failure atomically, or complete the success, in a short transaction.</li>
 * </ol>
 *
 * <p>bcrypt runs <em>outside</em> any transaction: a 200 ms hash inside one would hold a pooled
 * connection idle for the whole hash, and a burst of logins would drain the pool.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final MembershipQueries memberships;
    private final PasswordEncoder encoder;
    private final LoginAttemptService attempts;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final TransactionTemplate tx;
    private final PasswordPolicy policy;
    private final AuthAudit audit;
    private final Clock clock;

    /**
     * A real hash from the real encoder, computed at startup, never a literal: if the cost factor
     * changes, the dummy changes with it and unknown emails keep taking as long as known ones.
     */
    private final String dummyHash;

    AuthService(
            AppUserRepository users,
            MembershipQueries memberships,
            PasswordEncoder encoder,
            LoginAttemptService attempts,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
            TransactionTemplate tx,
            PasswordPolicy policy,
            AuthAudit audit,
            Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.attempts = attempts;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.tx = tx;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.dummyHash = encoder.encode(HexFormat.of().formatHex(random));
    }

    public LoginResult login(String email, String password, ClientInfo client) {
        Optional<AppUser> found = users.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            matches(password, dummyHash); // same cost as a real check; result irrelevant
            tx.executeWithoutResult(status -> audit.loginFailedUnknownEmail(email, client));
            return new LoginResult.Failure();
        }
        AppUser user = found.get();
        boolean passwordMatches = matches(password, user.getPasswordHash());

        if (!user.isActive() || user.isLockedAt(clock.instant())) {
            // Same 401 as a wrong password. A locked account is never confirmed (no 423), and
            // failures during a lock don't touch the counter.
            String reason = user.isActive() ? "locked" : user.getStatus().name().toLowerCase(java.util.Locale.ROOT);
            tx.executeWithoutResult(status -> audit.loginFailed(user.getId(), reason, client));
            return new LoginResult.Failure();
        }
        if (!passwordMatches) {
            recordWrongPassword(user.getId(), "bad-password", client);
            return new LoginResult.Failure();
        }
        return completeSuccess(user, password, client);
    }

    /**
     * Counter, lock and audit rows in one transaction that <em>commits</em>; returning (not
     * throwing) is what keeps the rollback from undoing them.
     */
    private void recordWrongPassword(UUID userId, String reason, ClientInfo client) {
        tx.executeWithoutResult(status -> {
            var outcome = attempts.recordFailure(userId);
            audit.loginFailed(userId, reason, client);
            outcome.filter(LoginAttemptService.FailureOutcome::justLocked)
                    .ifPresent(o -> audit.accountLocked(userId, o.failedLoginCount(), o.lockedUntil(), client));
        });
    }

    private LoginResult completeSuccess(AppUser candidate, String password, ClientInfo client) {
        return tx.execute(status -> {
            // Re-read inside the transaction: the account may have been locked or disabled while
            // bcrypt was running.
            AppUser user = users.findById(candidate.getId()).orElseThrow();
            if (!user.isActive() || user.isLockedAt(clock.instant())) {
                audit.loginFailed(user.getId(), "locked", client);
                return new LoginResult.Failure();
            }
            user.recordSuccessfulLogin(clock.instant());
            if (encoder.upgradeEncoding(user.getPasswordHash())) {
                // The cost factor (or algorithm) was raised since this hash was written.
                user.upgradePasswordHash(encoder.encode(password));
            }
            // A new token family per login; it exists only if this transaction commits.
            String refreshToken = refreshTokens.issue(user.getId());
            audit.loginSucceeded(user.getId(), client);
            return new LoginResult.Success(
                    user.getId(),
                    accessTokens.issue(user),
                    refreshToken,
                    memberships.activeMembershipsOf(user.getId()));
        });
    }

    /** The outcome of a password change; a wrong current password is returned, not thrown. */
    public sealed interface ChangeResult {
        record Changed(AccessToken accessToken, String refreshToken) implements ChangeResult {}

        record WrongPassword() implements ChangeResult {}
    }

    /**
     * Change-password (authentication.plan §4.5): verify the current password, apply the policy,
     * rehash, clear {@code must_change_password}, revoke every refresh token of the user (logging
     * out all other devices), and start a new family for this device.
     *
     * <p>The new pair is what avoids the first-login dead end: this device's refresh token was
     * just revoked and its access token still says {@code pwd_change}, so without a fresh pair the
     * user would change their password and immediately be locked out of everything.
     */
    public ChangeResult changePassword(UUID userId, String currentPassword, String newPassword, ClientInfo client) {
        policy.validate(newPassword);
        AppUser candidate = users.findById(userId).orElseThrow();
        if (!matches(currentPassword, candidate.getPasswordHash())) {
            // A stolen access token must not become an unlimited password oracle: count it.
            recordWrongPassword(userId, "bad-current-password", client);
            return new ChangeResult.WrongPassword();
        }
        String newHash = encoder.encode(newPassword); // bcrypt outside the transaction, as in login
        return tx.execute(status -> {
            AppUser user = users.findById(userId).orElseThrow();
            user.changePassword(newHash);
            int revoked = refreshTokens.revokeAllForUser(userId, RevocationReason.PASSWORD_CHANGED);
            String refreshToken = refreshTokens.issue(userId);
            audit.passwordChanged(userId, revoked, client);
            return new ChangeResult.Changed(accessTokens.issue(user), refreshToken);
        });
    }

    /**
     * bcrypt only reads 72 bytes and Spring's encoder refuses longer input; such a password can't
     * be valid (the policy caps it), so it fails, after the same amount of hashing work.
     */
    private boolean matches(String raw, String hash) {
        if (PasswordPolicy.utf8Bytes(raw) > PasswordPolicy.MAX_UTF8_BYTES) {
            encoder.matches("x", dummyHash);
            return false;
        }
        return encoder.matches(raw, hash);
    }
}
