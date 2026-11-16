package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.security.token.RefreshTokenService;
import com.reconflow.paymentreconflow.security.token.RevocationReason;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ConflictException;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-managed membership of the caller's organization (authentication.plan §4.6). There is no
 * sign-up: every account is created here (or by the platform flow) with a temporary password.
 *
 * <p>The organization is always {@code tenant.organizationId()}. Every lookup is by
 * {@code (userId, organizationId)}, so another organization's member is simply not found: 404,
 * the same as an ID that never existed (ADR 0008).
 */
@Service
public class MemberService {

    /** A member plus, for a newly created account, its one-time temporary password. */
    public record CreatedMember(MemberView member, @Nullable String temporaryPassword) {}

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final MemberQueries queries;
    private final PasswordEncoder encoder;
    private final RefreshTokenService refreshTokens;
    private final TenantContext tenant;
    private final CurrentUser currentUser;
    private final JdbcClient jdbc;
    private final EntityManager em;
    private final AuditService audit;

    MemberService(
            AppUserRepository users,
            MembershipRepository memberships,
            MemberQueries queries,
            PasswordEncoder encoder,
            RefreshTokenService refreshTokens,
            TenantContext tenant,
            CurrentUser currentUser,
            JdbcClient jdbc,
            EntityManager em,
            AuditService audit) {
        this.users = users;
        this.memberships = memberships;
        this.queries = queries;
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.em = em;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<MemberView> list(int page, int size, List<String> sort) {
        return queries.list(tenant.organizationId(), page, size, sort);
    }

    /**
     * Creates the account with a temporary password, or attaches an existing account (users are
     * global). Either way the answer depends only on <em>this</em> organization: "already a member
     * here" is 409, and nothing reveals whether the email belongs to another organization's user.
     */
    @Transactional
    public CreatedMember create(String email, String displayName, Role role) {
        UUID org = tenant.organizationId();
        var existing = users.findByEmailIgnoreCase(email.trim());
        String temporaryPassword = null;
        AppUser user;
        if (existing.isPresent()) {
            user = existing.get();
            if (memberships.findById(new MembershipId(user.getId(), org)).isPresent()) {
                throw new ConflictException(ProblemCodes.ALREADY_A_MEMBER, "This user is already a member.");
            }
        } else {
            temporaryPassword = TemporaryPasswords.generate();
            user = users.save(AppUser.create(email, displayName, encoder.encode(temporaryPassword), true));
        }
        memberships.save(Membership.create(user.getId(), org, role, currentUser.id()));
        audit.record(AuditEntry.of(AuditAction.MEMBER_CREATED, AuditEntityType.MEMBERSHIP, user.getId())
                .with("role", role)
                .with("newAccount", temporaryPassword != null));
        return new CreatedMember(view(org, user.getId()), temporaryPassword);
    }

    /** No extra step for permissions: they're resolved per request, so the new role applies at once. */
    @Transactional
    public MemberView changeRole(UUID userId, Role newRole) {
        UUID org = tenant.organizationId();
        lockOrganization(org);
        Membership membership = membership(org, userId);
        if (membership.getRole() == Role.ORG_ADMIN && newRole != Role.ORG_ADMIN) {
            requireAnotherActiveAdmin(org, userId);
        }
        Role previous = membership.getRole();
        membership.changeRole(newRole);
        audit.record(AuditEntry.of(AuditAction.MEMBER_ROLE_CHANGED, AuditEntityType.MEMBERSHIP, userId)
                .with("from", previous)
                .with("to", newRole));
        return view(org, userId);
    }

    /** A new temporary password, shown once; every session of the user is revoked. */
    @Transactional
    public String resetPassword(UUID userId) {
        UUID org = tenant.organizationId();
        membership(org, userId);
        String temporaryPassword = TemporaryPasswords.generate();
        users.findById(userId).orElseThrow().resetPassword(encoder.encode(temporaryPassword));
        int revoked = refreshTokens.revokeAllForUser(userId, RevocationReason.ADMIN_REVOKED);
        audit.record(AuditEntry.of(AuditAction.PASSWORD_RESET_BY_ADMIN, AuditEntityType.USER, userId)
                .with("revokedSessions", revoked));
        return temporaryPassword;
    }

    /**
     * Disables the account and revokes its sessions; TenantContextFilter rejects its access token
     * on the next request, because the status is checked every time.
     */
    @Transactional
    public MemberView disable(UUID userId) {
        UUID org = tenant.organizationId();
        lockOrganization(org);
        Membership membership = membership(org, userId);
        AppUser user = users.findById(userId).orElseThrow();
        if (membership.getRole() == Role.ORG_ADMIN && user.isActive()) {
            requireAnotherActiveAdmin(org, userId);
        }
        user.disable();
        int revoked = refreshTokens.revokeAllForUser(userId, RevocationReason.USER_DISABLED);
        audit.record(AuditEntry.of(AuditAction.MEMBER_DISABLED, AuditEntityType.USER, userId)
                .with("revokedSessions", revoked));
        return view(org, userId);
    }

    private Membership membership(UUID org, UUID userId) {
        return memberships
                .findById(new MembershipId(userId, org))
                .orElseThrow(() -> new NotFoundException("Member", userId));
    }

    /**
     * Serializes admin changes per organization. Locking the organization row, not the membership
     * rows, is what makes two admins demoting <em>each other</em> at the same moment wait for one
     * another: they touch different memberships, so membership locks would let both through, both
     * would count one remaining admin, and the organization would end up with none.
     */
    private void lockOrganization(UUID org) {
        jdbc.sql("SELECT id FROM organization WHERE id = :org FOR UPDATE")
                .param("org", org)
                .query(UUID.class)
                .single();
    }

    /** Only ACTIVE admins count: a disabled admin can't administer anything. */
    private void requireAnotherActiveAdmin(UUID org, UUID targetUserId) {
        long others = jdbc.sql("""
                        SELECT count(*) FROM membership m JOIN app_user u ON u.id = m.user_id
                         WHERE m.organization_id = :org AND m.role = 'ORG_ADMIN'
                           AND u.status = 'ACTIVE' AND m.user_id <> :target
                        """)
                .param("org", org)
                .param("target", targetUserId)
                .query(Long.class)
                .single();
        if (others == 0) {
            throw new ConflictException(
                    ProblemCodes.LAST_ORG_ADMIN, "The organization must keep at least one active org admin.");
        }
    }

    /** Flushes first so the view reads the row as the database has it (defaults included). */
    private MemberView view(UUID org, UUID userId) {
        em.flush();
        return queries.find(org, userId).orElseThrow();
    }
}
