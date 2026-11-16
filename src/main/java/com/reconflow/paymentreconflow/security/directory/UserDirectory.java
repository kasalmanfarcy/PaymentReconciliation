package com.reconflow.paymentreconflow.security.directory;

import com.reconflow.paymentreconflow.shared.context.Permission;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The one part of {@code security} other modules may call (architecture.md §4): "may this user
 * work in this organization?" and "what are these users called?". The {@code exceptions} module
 * uses it to validate assignees and to show names, without reading {@code membership} or
 * {@code app_user} itself.
 */
public interface UserDirectory {

    /**
     * True if the user is ACTIVE and a member of the organization whose role grants the permission.
     * A user of another organization, a disabled user or an unknown id are all simply false: the
     * caller can't tell them apart (ADR 0008).
     */
    boolean isActiveMemberWith(UUID organizationId, UUID userId, Permission permission);

    /** Display names of the given users, in one query; unknown ids are absent from the map. */
    Map<UUID, String> displayNames(Collection<UUID> userIds);
}
