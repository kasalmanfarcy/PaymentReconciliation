package com.reconflow.paymentreconflow.security.auth;

import static com.reconflow.paymentreconflow.audit.AuditEntityType.REFRESH_TOKEN_FAMILY;
import static com.reconflow.paymentreconflow.audit.AuditEntityType.USER;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.security.token.TokenHasher;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Audit rows for authentication events (M3.3h). Login is global, so these carry no organization
 * (NULL, which RLS accepts when no tenant is bound). The actor is the user when known.
 *
 * <p>Never a password or a token, raw or hashed. For an unknown email, a SHA-256 of the lowercased
 * address: a credential-stuffing run can be correlated across attempts without storing arbitrary
 * attacker input (or a real person's typo of their email) in a log kept for years.
 *
 * <p>Every method joins the caller's transaction (AuditService is MANDATORY), so each event commits
 * or rolls back with the change it describes.
 */
@Component
public class AuthAudit {

    private final AuditService audit;

    AuthAudit(AuditService audit) {
        this.audit = audit;
    }

    void loginSucceeded(UUID userId, ClientInfo client) {
        audit.record(entry(AuditAction.LOGIN_SUCCEEDED, userId, client));
    }

    void loginFailed(@Nullable UUID userId, String reason, ClientInfo client) {
        audit.record(entry(AuditAction.LOGIN_FAILED, userId, client).with("reason", reason));
    }

    void loginFailedUnknownEmail(String email, ClientInfo client) {
        audit.record(entry(AuditAction.LOGIN_FAILED, null, client)
                .with("reason", "unknown-email")
                .with("emailSha256", TokenHasher.sha256Hex(email.trim().toLowerCase(Locale.ROOT))));
    }

    void accountLocked(UUID userId, int failedLoginCount, Instant lockedUntil, ClientInfo client) {
        audit.record(entry(AuditAction.ACCOUNT_LOCKED, userId, client)
                .with("failedLoginCount", failedLoginCount)
                .with("lockedUntil", lockedUntil.toString()));
    }

    void passwordChanged(UUID userId, int revokedSessions, ClientInfo client) {
        audit.record(entry(AuditAction.PASSWORD_CHANGED, userId, client).with("revokedSessions", revokedSessions));
    }

    public void logout(UUID userId, UUID familyId, ClientInfo client) {
        audit.record(entry(AuditAction.LOGOUT, userId, client).with("familyId", familyId));
    }

    public void refreshTokenReuse(UUID userId, UUID familyId, int revokedTokens) {
        audit.record(AuditEntry.of(AuditAction.REFRESH_TOKEN_REUSE, REFRESH_TOKEN_FAMILY, familyId)
                .actorId(userId)
                .with("userId", userId)
                .with("revokedTokens", revokedTokens));
    }

    private static AuditEntry entry(AuditAction action, @Nullable UUID userId, ClientInfo client) {
        return AuditEntry.of(action, USER, userId)
                .actorId(userId)
                .with("ip", client.ip())
                .with("userAgent", client.userAgent());
    }
}
