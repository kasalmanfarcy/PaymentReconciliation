package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.shared.context.Permission;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Replaces the resource server's JwtAuthenticationToken once the tenant is resolved. Its
 * authorities are the caller's <em>permissions</em> in the selected organization (or
 * {@code PLATFORM_ORG_MANAGE} on platform routes), looked up for this request, so
 * {@code hasAuthority(...)} in URL rules and {@code @PreAuthorize} see current rights, not what
 * was true when the token was issued. The principal stays the Jwt.
 */
public class TenantAuthentication extends AbstractAuthenticationToken {

    private final Jwt jwt;

    TenantAuthentication(Jwt jwt, Collection<Permission> permissions) {
        super(toAuthorities(permissions));
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt.getTokenValue();
    }

    @Override
    public Jwt getPrincipal() {
        return jwt;
    }

    @Override
    public String getName() {
        return jwt.getSubject();
    }

    private static Collection<GrantedAuthority> toAuthorities(Collection<Permission> permissions) {
        return permissions.stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .toList();
    }
}
