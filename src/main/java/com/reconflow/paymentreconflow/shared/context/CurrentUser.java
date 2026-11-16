package com.reconflow.paymentreconflow.shared.context;

import java.util.Optional;
import java.util.UUID;

/** Who is acting. Empty for system work (jobs, sweepers): audit rows then get a NULL actor. */
public interface CurrentUser {

    /** @throws IllegalStateException if no user is bound (system context). */
    UUID id();

    Optional<UUID> idIfPresent();
}
