package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.organization.Organization;
import com.reconflow.paymentreconflow.organization.OrganizationService;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.shared.id.Ids;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Platform admins create organizations together with their first ORG_ADMIN. Platform admins get
 * no access to an organization's data: they manage the tenant list, nothing inside it.
 */
@Service
public class PlatformOrganizationService {

    public record CreatedOrganization(
            Organization organization,
            UUID adminUserId,
            String adminEmail,
            @Nullable String temporaryPassword) {}

    private final OrganizationService organizations;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final TenantScope tenantScope;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final AuditService audit;

    PlatformOrganizationService(
            OrganizationService organizations,
            AppUserRepository users,
            MembershipRepository memberships,
            PasswordEncoder encoder,
            TenantScope tenantScope,
            CurrentUser currentUser,
            TransactionTemplate tx,
            AuditService audit) {
        this.organizations = organizations;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.tenantScope = tenantScope;
        this.currentUser = currentUser;
        this.tx = tx;
        this.audit = audit;
    }

    public Page<Organization> list(int page, int size) {
        int pageSize = size < 1 ? 50 : Math.min(size, 200);
        return organizations.list(PageRequest.of(
                Math.max(page, 0), pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    /**
     * One transaction: the organization, its first admin (new, or an existing account attached)
     * and their audit rows commit together. The new organization is bound as the tenant
     * <em>before</em> the transaction starts, so the database's tenant (RLS) is the new org and its
     * audit rows are accepted; the platform admin is recorded explicitly as the actor, because
     * inside TenantScope the bound actor is the system.
     */
    public CreatedOrganization create(String name, String adminEmail, String adminDisplayName) {
        UUID platformAdmin = currentUser.id();
        UUID orgId = Ids.newId();
        return tenantScope.call(
                orgId,
                () -> tx.execute(status -> {
                    Organization org = organizations.create(orgId, name.trim(), platformAdmin);
                    var existing = users.findByEmailIgnoreCase(adminEmail.trim());
                    String temporaryPassword = null;
                    AppUser admin;
                    if (existing.isPresent()) {
                        admin = existing.get();
                    } else {
                        temporaryPassword = TemporaryPasswords.generate();
                        admin = users.save(
                                AppUser.create(adminEmail, adminDisplayName, encoder.encode(temporaryPassword), true));
                    }
                    memberships.save(Membership.create(admin.getId(), orgId, Role.ORG_ADMIN, platformAdmin));
                    audit.record(AuditEntry.of(AuditAction.MEMBER_CREATED, AuditEntityType.MEMBERSHIP, admin.getId())
                            .actorId(platformAdmin)
                            .with("role", Role.ORG_ADMIN)
                            .with("newAccount", temporaryPassword != null));
                    return new CreatedOrganization(org, admin.getId(), admin.getEmail(), temporaryPassword);
                }));
    }
}
