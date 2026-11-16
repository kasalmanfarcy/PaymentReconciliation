package com.reconflow.paymentreconflow.security.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brute-force lockout: 5 failures lock the account for 15 minutes (authentication.plan §4.1).
 *
 * <p>One atomic {@code UPDATE ... RETURNING}, never read-modify-write:
 * <ul>
 *   <li>A read-modify-write lets concurrent attempts overwrite each other's increments, giving an
 *       attacker more than five guesses. Row locking inside one statement can't lose one.</li>
 *   <li>An expired lock restarts the count at 1 <em>in the same statement</em>. Without the
 *       restart, one failure after expiry re-locks immediately, so an attacker could keep a real
 *       user locked out forever at one guess per 15 minutes; as a separate statement it would
 *       reopen the race.</li>
 *   <li>While locked, the WHERE matches no row: failures during a lock don't extend it.</li>
 * </ul>
 */
@Service
public class LoginAttemptService {

    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 15;

    /** The counter after this failure; {@code lockedUntil} is set only when this failure locked. */
    public record FailureOutcome(
            int failedLoginCount, @Nullable Instant lockedUntil) {

        public boolean justLocked() {
            return lockedUntil != null;
        }
    }

    private final JdbcClient jdbc;

    LoginAttemptService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one failed password. Empty when the account is currently locked (nothing changed).
     * Runs in the caller's transaction, which must commit: rolling back would undo the count.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<FailureOutcome> recordFailure(UUID userId) {
        return jdbc.sql("""
                        UPDATE app_user SET
                          failed_login_count = CASE WHEN locked_until <= now() THEN 1
                                                    ELSE failed_login_count + 1 END,
                          locked_until = CASE
                              WHEN (CASE WHEN locked_until <= now() THEN 1
                                         ELSE failed_login_count + 1 END) >= :maxFailures
                              THEN now() + make_interval(mins => :lockMinutes) END,
                          updated_at = now()
                        WHERE id = :id
                          AND (locked_until IS NULL OR locked_until <= now())
                        RETURNING failed_login_count, locked_until
                        """)
                .param("maxFailures", MAX_FAILURES)
                .param("lockMinutes", LOCK_MINUTES)
                .param("id", userId)
                .query((rs, n) -> new FailureOutcome(
                        rs.getInt("failed_login_count"),
                        rs.getTimestamp("locked_until") == null
                                ? null
                                : rs.getTimestamp("locked_until").toInstant()))
                .optional();
    }
}
