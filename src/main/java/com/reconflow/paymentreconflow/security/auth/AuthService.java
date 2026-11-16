package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.token.AccessTokenService;
import com.reconflow.paymentreconflow.security.token.RefreshTokenService;
import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.MembershipQueries;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
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
            Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.attempts = attempts;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.tx = tx;
        this.clock = clock;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.dummyHash = encoder.encode(HexFormat.of().formatHex(random));
    }

    public LoginResult login(String email, String password) {
        Optional<AppUser> found = users.findByEmailIgnoreCase(email.trim());
        if (found.isEmpty()) {
            matches(password, dummyHash); // same cost as a real check; result irrelevant
            return new LoginResult.Failure();
        }
        AppUser user = found.get();
        boolean passwordMatches = matches(password, user.getPasswordHash());

        if (!user.isActive() || user.isLockedAt(clock.instant())) {
            // Same 401 as a wrong password. A locked account is never confirmed (no 423), and
            // failures during a lock don't touch the counter.
            return new LoginResult.Failure();
        }
        if (!passwordMatches) {
            tx.executeWithoutResult(status -> attempts.recordFailure(user.getId()));
            return new LoginResult.Failure();
        }
        return completeSuccess(user, password);
    }

    private LoginResult completeSuccess(AppUser candidate, String password) {
        return tx.execute(status -> {
            // Re-read inside the transaction: the account may have been locked or disabled while
            // bcrypt was running.
            AppUser user = users.findById(candidate.getId()).orElseThrow();
            if (!user.isActive() || user.isLockedAt(clock.instant())) {
                return new LoginResult.Failure();
            }
            user.recordSuccessfulLogin(clock.instant());
            if (encoder.upgradeEncoding(user.getPasswordHash())) {
                // The cost factor (or algorithm) was raised since this hash was written.
                user.upgradePasswordHash(encoder.encode(password));
            }
            // A new token family per login; it exists only if this transaction commits.
            String refreshToken = refreshTokens.issue(user.getId());
            return new LoginResult.Success(
                    user.getId(),
                    accessTokens.issue(user),
                    refreshToken,
                    memberships.activeMembershipsOf(user.getId()));
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
