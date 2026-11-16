package com.reconflow.paymentreconflow.tenancy;

import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One endpoint's cross-tenant check: org A's most privileged user calls it, aimed at org B's data.
 *
 * @param pathTemplate exactly as mapped, e.g. {@code /api/v1/members/{userId}}; the completeness
 *     meta-test matches on it
 * @param orgBTarget the path variables, pointing at org B's resources (created by the fixture)
 * @param body the request body, or null
 */
public record CrossTenantCase(
        String name,
        HttpMethod method,
        String pathTemplate,
        Function<TenantFixture, Map<String, Object>> orgBTarget,
        @Nullable Function<TenantFixture, Object> body,
        Expectation expect) {

    public enum Expectation {
        /** 404 not-found, exactly as for an ID that never existed. */
        NOT_FOUND,
        /** 2xx, and no ID of org B anywhere in the response. */
        LIST_EXCLUDES_B,
        /** 2xx for org A's own data; nothing of org B changes. */
        AFFECTS_ONLY_A
    }

    public String key() {
        return method.name() + " " + pathTemplate;
    }

    @Override
    public String toString() {
        return name;
    }
}
