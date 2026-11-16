package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * M3.9's completeness meta-test. TASKS.md says "keep adding cross-tenant tests as endpoints
 * appear"; a rule that relies on remembering is forgotten once, so this turns forgetting into a red
 * build: every mapped endpoint needs a registered {@link CrossTenantCase} or an explicit exemption.
 */
class CrossTenantCoverageIT extends AbstractIntegrationTest {

    /**
     * Not tenant data: login/refresh/logout/change-password act on the caller only, platform routes
     * are covered by PlatformOrganizationIT (org roles get 403, platform admins get 404 on org
     * routes), and /error is the container's error page.
     */
    static final List<String> EXEMPT_PREFIXES = List.of("/api/v1/auth/", "/api/v1/platform/", "/error");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Autowired
    List<CrossTenantCases> registries;

    static Set<String> missing(Set<String> endpoints, Set<String> covered) {
        return endpoints.stream()
                .filter(e -> EXEMPT_PREFIXES.stream()
                        .noneMatch(p -> e.substring(e.indexOf(' ') + 1).startsWith(p)))
                .filter(e -> !covered.contains(e))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> endpoints() {
        Set<String> endpoints = new TreeSet<>();
        mappings.getHandlerMethods()
                .keySet()
                .forEach(info -> info.getMethodsCondition()
                        .getMethods()
                        .forEach(method ->
                                info.getPatternValues().forEach(path -> endpoints.add(method.name() + " " + path))));
        return endpoints;
    }

    private Set<String> covered() {
        return registries.stream()
                .flatMap(r -> r.cases().stream())
                .map(CrossTenantCase::key)
                .collect(Collectors.toSet());
    }

    @Test
    void everyEndpointHasACrossTenantCaseOrIsExempt() {
        assertThat(missing(endpoints(), covered()))
                .as("add a CrossTenantCase (next to the module's tests) for each of these")
                .isEmpty();
    }

    @Test
    void deletingACaseFailsTheCheck() {
        Set<String> covered = new TreeSet<>(covered());
        String removed = covered.iterator().next();
        covered.remove(removed);

        assertThat(missing(endpoints(), covered)).containsExactly(removed);
    }
}
