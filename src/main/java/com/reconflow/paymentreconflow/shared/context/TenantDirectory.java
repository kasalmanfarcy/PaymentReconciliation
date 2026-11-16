package com.reconflow.paymentreconflow.shared.context;

import java.util.List;
import java.util.UUID;

/**
 * Every organization's id, for system work that must visit each tenant in turn (M9.1's consistency
 * check, M9.9's gauges). Row-level security hides every tenant-owned row unless one organization is
 * bound, and the application connects as a role that can't bypass it (FORCE RLS), so a
 * cross-tenant count is "for each organization, bind it, count". That is the deliberate system path:
 * no BYPASSRLS role, no SECURITY DEFINER function that would quietly widen what the app can read.
 *
 * <p>Declared here, implemented by {@code organization} (which owns the table), so other modules
 * don't depend on it.
 */
public interface TenantDirectory {

    /** All organizations, suspended ones included (their stuck work still needs recovering). */
    List<UUID> organizationIds();
}
