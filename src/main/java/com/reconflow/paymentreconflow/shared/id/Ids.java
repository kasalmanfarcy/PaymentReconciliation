package com.reconflow.paymentreconflow.shared.id;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;

/**
 * The one place IDs are generated (ADR 0010), so the choice of generator lives in one class.
 *
 * <p>UUIDv7 puts a millisecond timestamp in the high bits: B-tree inserts land near the right
 * edge of the index (unlike random v4), yet IDs are not sequential, so they are safe in URLs.
 * Assigning the ID in the application, before insert, means it is known up front: it can go
 * into the audit event, the Location header and a storage key in the same transaction, and
 * Hibernate can batch inserts without a round trip per row.
 */
public final class Ids {

    private Ids() {}

    /**
     * A new time-ordered UUIDv7. Within the same millisecond the generator increments the
     * random part instead of drawing a new one, so IDs from this JVM are strictly increasing.
     */
    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
