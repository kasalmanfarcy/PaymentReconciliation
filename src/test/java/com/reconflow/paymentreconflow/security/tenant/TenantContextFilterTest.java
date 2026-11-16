package com.reconflow.paymentreconflow.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.reconflow.paymentreconflow.security.tenant.TenantLookup.MembershipRow;
import com.reconflow.paymentreconflow.security.tenant.TenantLookup.UserMemberships;
import com.reconflow.paymentreconflow.security.user.UserStatus;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.error.ProblemDetailWriter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/** The filter binds the tenant for the rest of the request, and only for it. */
class TenantContextFilterTest {

    private final UUID user = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private final TenantLookup lookup = mock(TenantLookup.class);
    private final ScopedTenantHolder holder = new ScopedTenantHolder();
    private final TenantContextFilter filter = new TenantContextFilter(lookup, newWriter());

    private static ProblemDetailWriter newWriter() {
        try {
            var ctor = ProblemDetailWriter.class.getDeclaredConstructor(JsonMapper.class);
            ctor.setAccessible(true);
            return ctor.newInstance(JsonMapper.builder().build());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(user.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void theControllerSeesTheResolvedTenantAndPermissionsBecomeAuthorities() throws Exception {
        authenticate();
        when(lookup.find(user))
                .thenReturn(Optional.of(new UserMemberships(
                        UserStatus.ACTIVE, false, List.of(new MembershipRow(org, Role.ORG_ADMIN, true)))));
        var seenOrg = new AtomicReference<UUID>();
        var seenAuthorities = new AtomicReference<List<String>>();
        var request = new MockHttpServletRequest("GET", "/api/v1/members");
        request.addHeader("X-Organization-Id", org.toString());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seenOrg.set(holder.organizationId());
                seenAuthorities.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList());
            }
        });

        assertThat(seenOrg.get()).isEqualTo(org);
        assertThat(seenAuthorities.get()).contains(Permission.MEMBER_MANAGE.name());
        // Unbound once the request is done: nothing leaks to the thread's next request.
        assertThat(holder.organizationIdIfPresent()).isEmpty();
    }

    @Test
    void aRejectionIsWrittenAsAProblemAndTheChainNeverRuns() throws Exception {
        authenticate();
        when(lookup.find(user)).thenReturn(Optional.of(new UserMemberships(UserStatus.ACTIVE, false, List.of())));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/members"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":\"organization-not-found\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unauthenticatedRequestsPassThroughUntouched() throws Exception {
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/members"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull(); // authorization will answer 401
    }
}
