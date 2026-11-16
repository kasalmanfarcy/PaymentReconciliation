package com.reconflow.paymentreconflow.security.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** The composite primary key {@code (user_id, organization_id)}. */
@Embeddable
public record MembershipId(
        @Column(name = "user_id") UUID userId,
        @Column(name = "organization_id") UUID organizationId) implements Serializable {}
