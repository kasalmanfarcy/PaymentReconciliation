package com.reconflow.paymentreconflow.exceptions.internal.application;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A user as the API shows one: id and display name. */
public record UserRef(UUID id, @Nullable String displayName) {

    static @Nullable UserRef of(@Nullable UUID id, Map<UUID, String> names) {
        return id == null ? null : new UserRef(id, names.get(id));
    }
}
