package com.reconflow.paymentreconflow.security.user;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.AFFECTS_ONLY_A;
import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.LIST_EXCLUDES_B;
import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.NOT_FOUND;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** The /members endpoints' cross-tenant cases (M3.9). */
@Component
class MemberCrossTenantCases implements CrossTenantCases {

    @Override
    public List<CrossTenantCase> cases() {
        return List.of(
                new CrossTenantCase(
                        "list members", HttpMethod.GET, "/api/v1/members", f -> Map.of(), null, LIST_EXCLUDES_B),
                // Users are global, so adding org B's user to A by email is allowed: it creates a
                // membership in A and must leave everything of B, the account included, untouched.
                new CrossTenantCase(
                        "add org B's user to org A",
                        HttpMethod.POST,
                        "/api/v1/members",
                        f -> Map.of(),
                        f -> Map.of(
                                "email", f.orgBMember(Role.ORG_ADMIN).email(),
                                "displayName", "Hijack",
                                "role", "ORG_ADMIN"),
                        AFFECTS_ONLY_A),
                new CrossTenantCase(
                        "change the role of org B's member",
                        HttpMethod.PATCH,
                        "/api/v1/members/{userId}",
                        f -> Map.of("userId", f.orgBMember(Role.VIEWER).id()),
                        f -> Map.of("role", "ORG_ADMIN"),
                        NOT_FOUND),
                new CrossTenantCase(
                        "reset the password of org B's admin",
                        HttpMethod.POST,
                        "/api/v1/members/{userId}/reset-password",
                        f -> Map.of("userId", f.orgBMember(Role.ORG_ADMIN).id()),
                        null,
                        NOT_FOUND),
                new CrossTenantCase(
                        "disable org B's admin",
                        HttpMethod.POST,
                        "/api/v1/members/{userId}/disable",
                        f -> Map.of("userId", f.orgBMember(Role.ORG_ADMIN).id()),
                        null,
                        NOT_FOUND));
    }
}
