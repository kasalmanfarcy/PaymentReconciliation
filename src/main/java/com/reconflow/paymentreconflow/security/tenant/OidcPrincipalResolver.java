package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Links Keycloak users to {@code app_user} rows by {@code sub}, stored in
 * {@code app_user.external_subject} (M10.1, V12).
 *
 * <ul>
 *   <li>Normal case: one indexed lookup by {@code external_subject}.</li>
 *   <li>First login of a user an org admin pre-provisioned in ReconFlow (or one who existed before
 *       the migration): link by email, <strong>only if Keycloak says the email is verified</strong>,
 *       and only a row not linked to anyone yet. Linking by an unverified email would be an account
 *       takeover: anyone able to register that address at the IdP would become that user.</li>
 * </ul>
 *
 * <p>Email is never used again after the link: emails change, {@code sub} doesn't. The link is
 * audited ({@code USER_LINKED}, a platform event: no organization is known yet). No cache: at the
 * planned ~35 requests/s one primary-key-like lookup per request is nothing, and a cache would delay
 * an unlink.
 */
@Component
class OidcPrincipalResolver implements PrincipalResolver {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;

    OidcPrincipalResolver(JdbcClient jdbc, TransactionTemplate tx, AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
    }

    @Override
    public Optional<UUID> resolveUserId(Jwt jwt) {
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            return Optional.empty();
        }
        Optional<UUID> linked = jdbc.sql("SELECT id FROM app_user WHERE external_subject = :sub")
                .param("sub", subject)
                .query(UUID.class)
                .optional();
        if (linked.isPresent()) {
            return linked;
        }
        String email = jwt.getClaimAsString("email");
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) || !StringUtils.hasText(email)) {
            return Optional.empty();
        }
        return Optional.ofNullable(tx.execute(status -> {
            // Conditional UPDATE: of two concurrent first requests, only one matches the row; the
            // other finds it already linked (to the same sub) on its next request.
            Optional<UUID> id = jdbc.sql("""
                            UPDATE app_user SET external_subject = :sub, updated_at = now()
                             WHERE lower(email) = lower(:email) AND external_subject IS NULL
                            RETURNING id""")
                    .param("sub", subject)
                    .param("email", email)
                    .query(UUID.class)
                    .optional();
            id.ifPresent(userId -> audit.record(AuditEntry.of(AuditAction.USER_LINKED, AuditEntityType.USER, userId)
                    .actorId(userId)));
            return id.orElse(null);
        }));
    }
}
