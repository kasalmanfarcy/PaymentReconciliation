package com.reconflow.paymentreconflow.security.token;

import com.reconflow.paymentreconflow.security.config.AuthProperties;
import com.reconflow.paymentreconflow.shared.id.Ids;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh tokens with rotation and reuse detection (authentication.plan §3, §4.3).
 *
 * <p>Every use replaces the token. Presenting a token that was already rotated means two parties
 * hold copies (the user and a thief), so the whole family is revoked: both must log in again,
 * and only the real user can.
 */
@Service
public class RefreshTokenService {

    /** What happened to a presented token. */
    public sealed interface RotationResult {
        /** The replacement to send back; the presented one is now ROTATED. */
        record Rotated(UUID userId, UUID familyId, String newToken) implements RotationResult {}

        /** A rotated token came back: the family is revoked. The caller answers 401. */
        record ReuseDetected(UUID userId, UUID familyId) implements RotationResult {}

        /** Unknown, expired, or revoked for an ordinary reason: 401, and no alarm. */
        record Rejected() implements RotationResult {}
    }

    /** The row as read in step 1; transaction A needs user, family and family cap from it. */
    record Row(
            UUID id,
            UUID userId,
            UUID familyId,
            OffsetDateTime expiresAt,
            OffsetDateTime familyExpiresAt,
            @Nullable OffsetDateTime revokedAt,
            @Nullable RevocationReason revokedReason) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuthProperties properties;

    RefreshTokenService(JdbcClient jdbc, TransactionTemplate tx, AuthProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
    }

    /**
     * Starts a new family (a login, or a password change on the calling device) and returns the
     * raw token, which exists only in the response cookie from here on. Joins the caller's
     * transaction so the token exists only if the login commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(UUID userId) {
        String raw = TokenHasher.newToken();
        jdbc.sql("""
                        INSERT INTO refresh_token (id, user_id, family_id, token_hash, expires_at, family_expires_at)
                        VALUES (:id, :userId, :familyId, :hash,
                                now() + least(make_interval(secs => :tokenTtl), make_interval(secs => :familyTtl)),
                                now() + make_interval(secs => :familyTtl))
                        """)
                .param("id", Ids.newId())
                .param("userId", userId)
                .param("familyId", Ids.newId())
                .param("hash", TokenHasher.sha256Hex(raw))
                .param("tokenTtl", properties.refresh().tokenTtl().toSeconds())
                .param("familyTtl", properties.refresh().familyTtl().toSeconds())
                .update();
        return raw;
    }

    /**
     * Read, decide, then exactly one of two separate transactions. Must be called outside a
     * transaction: each outcome commits on its own, which is the point (see {@link #revokeFamilyForReuse}).
     */
    public RotationResult rotate(String rawToken) {
        String hash = TokenHasher.sha256Hex(rawToken);
        Optional<Row> row = read(hash);
        RotationResult result = decide(row);
        if (result != null) {
            return result;
        }
        Row live = row.orElseThrow();
        Optional<RotationResult> rotated = tryRotate(live);
        if (rotated.isPresent()) {
            return rotated.get();
        }
        // Lost the race: a concurrent request claimed this token first. Re-read; it is now
        // ROTATED, so this request is treated exactly like a replay.
        RotationResult afterRace = decide(read(hash));
        return afterRace != null ? afterRace : new RotationResult.Rejected();
    }

    /** Step 2. Null means "live: go ahead and rotate". */
    private @Nullable RotationResult decide(Optional<Row> found) {
        if (found.isEmpty()) {
            return new RotationResult.Rejected();
        }
        Row row = found.get();
        if (row.revokedAt() != null) {
            if (row.revokedReason() == RevocationReason.ROTATED) {
                revokeFamilyForReuse(row);
                return new RotationResult.ReuseDetected(row.userId(), row.familyId());
            }
            return new RotationResult.Rejected(); // logout, password change, admin: no alarm
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!row.expiresAt().isAfter(now) || !row.familyExpiresAt().isAfter(now)) {
            return new RotationResult.Rejected();
        }
        return null;
    }

    /**
     * Transaction A. The replacement is inserted <em>first</em>: {@code replaced_by} has a foreign
     * key to it, so claiming the old row first would violate it. The claim is a conditional
     * UPDATE; under READ COMMITTED a concurrent claim makes it wait, re-check
     * {@code revoked_at IS NULL}, match 0 rows, and roll back (discarding the insert).
     */
    private Optional<RotationResult> tryRotate(Row old) {
        return Optional.ofNullable(tx.execute(status -> {
            String raw = TokenHasher.newToken();
            UUID newId = Ids.newId();
            jdbc.sql("""
                            INSERT INTO refresh_token (id, user_id, family_id, token_hash, expires_at, family_expires_at)
                            VALUES (:id, :userId, :familyId, :hash,
                                    least(now() + make_interval(secs => :tokenTtl), :familyExpiresAt),
                                    :familyExpiresAt)
                            """)
                    .param("id", newId)
                    .param("userId", old.userId())
                    .param("familyId", old.familyId())
                    .param("hash", TokenHasher.sha256Hex(raw))
                    .param("tokenTtl", properties.refresh().tokenTtl().toSeconds())
                    // least(): a token rotated on day 29 must not outlive its family's 30-day cap.
                    .param("familyExpiresAt", old.familyExpiresAt())
                    .update();
            int claimed =
                    jdbc.sql("""
                            UPDATE refresh_token
                               SET revoked_at = now(), revoked_reason = 'ROTATED', replaced_by = :newId
                             WHERE id = :oldId
                               AND revoked_at IS NULL
                               AND expires_at > now()
                               AND family_expires_at > now()
                            """).param("newId", newId).param("oldId", old.id()).update();
            if (claimed == 0) {
                status.setRollbackOnly();
                return null;
            }
            return new RotationResult.Rotated(old.userId(), old.familyId(), raw);
        }));
    }

    /**
     * Transaction B: reuse detected. A transaction of its <em>own</em>, committed before the 401
     * goes out. Kept apart from transaction A on purpose: if the revocation shared a transaction
     * that ends in ROLLBACK, the stolen family would stay live, nothing would be recorded, and the
     * response would still be 401, so every test that checks only the status would pass.
     */
    private void revokeFamilyForReuse(Row row) {
        tx.executeWithoutResult(
                status -> jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = now(), revoked_reason = 'REUSE_DETECTED'
                         WHERE family_id = :familyId AND revoked_at IS NULL
                        """).param("familyId", row.familyId()).update());
    }

    /** Revokes the family's live tokens (logout). Joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeFamily(UUID familyId, RevocationReason reason) {
        return jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = now(), revoked_reason = :reason
                         WHERE family_id = :familyId AND revoked_at IS NULL
                        """)
                .param("reason", reason.name())
                .param("familyId", familyId)
                .update();
    }

    /** Revokes every live token of a user (password change, admin reset, disable). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int revokeAllForUser(UUID userId, RevocationReason reason) {
        return jdbc.sql("""
                        UPDATE refresh_token SET revoked_at = now(), revoked_reason = :reason
                         WHERE user_id = :userId AND revoked_at IS NULL
                        """)
                .param("reason", reason.name())
                .param("userId", userId)
                .update();
    }

    /** The family of a presented token, if the token is known (logout). */
    public Optional<Row> find(String rawToken) {
        return read(TokenHasher.sha256Hex(rawToken));
    }

    private Optional<Row> read(String hash) {
        return jdbc.sql("""
                        SELECT id, user_id, family_id, expires_at, family_expires_at, revoked_at, revoked_reason
                          FROM refresh_token WHERE token_hash = :hash
                        """)
                .param("hash", hash)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("family_expires_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class),
                        rs.getString("revoked_reason") == null
                                ? null
                                : RevocationReason.valueOf(rs.getString("revoked_reason"))))
                .optional();
    }
}
