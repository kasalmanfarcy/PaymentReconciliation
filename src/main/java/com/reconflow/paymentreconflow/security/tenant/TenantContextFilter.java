package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.security.tenant.TenantResolver.Resolution;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ProblemDetailWriter;
import com.reconflow.paymentreconflow.shared.error.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves who is calling and for which organization, once per request (M3.4,
 * authentication.plan §4.2, §6), then runs the rest of the request with that binding.
 *
 * <ul>
 *   <li>Org routes ({@code /api/v1/**}): one join query, the {@code X-Organization-Id} rules, then
 *       the role's permissions become the request's authorities.</li>
 *   <li>Platform routes ({@code /api/v1/platform/**}): app_user only; PLATFORM_ORG_MANAGE if the
 *       database flag says so. Not skipped: this filter is the only thing that grants it.</li>
 *   <li>Anything else authenticated (actuator): an active user, no tenant.</li>
 * </ul>
 *
 * Runs after bearer authentication and before authorization, so a non-member gets 404 before any
 * permission check and never learns whether they'd have had the right. Errors are written here as
 * ProblemDetails, since an exception thrown in a filter never reaches the controller advice.
 *
 * <p>Not cached between requests, deliberately. Not a bean, so Boot doesn't register it as a
 * second, plain servlet filter.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final String API = "/api/v1/";
    private static final String PLATFORM = "/api/v1/platform/";
    private final PrincipalResolver principals;
    private final TenantLookup lookup;
    private final ProblemDetailWriter problemWriter;

    public TenantContextFilter(PrincipalResolver principals, TenantLookup lookup, ProblemDetailWriter problemWriter) {
        this.principals = principals;
        this.lookup = lookup;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Public routes (health, info) and unauthenticated requests carry no bearer identity;
        // authorization decides what happens to them.
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwtAuth)
                || isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }
        // M10.1: the token's subject is Keycloak's, not ours. A validly signed token for an account
        // ReconFlow doesn't know (never provisioned, or an unverified email) is still a 401.
        Optional<UUID> resolved = principals.resolveUserId(jwtAuth.getToken());
        if (resolved.isEmpty()) {
            write(
                    request,
                    response,
                    rejected(new Resolution.Rejected(
                            HttpStatus.UNAUTHORIZED,
                            ProblemCodes.UNAUTHENTICATED,
                            "Authentication is required.",
                            null)));
            return;
        }
        UUID userId = resolved.get();

        String path = path(request);
        var user = lookup.find(userId);
        Resolution resolution = path.startsWith(PLATFORM)
                ? TenantResolver.platform(userId, user)
                : path.startsWith(API)
                        ? TenantResolver.tenant(userId, user, request.getHeader(TenantResolver.HEADER))
                        : TenantResolver.identity(userId, user);

        switch (resolution) {
            case Resolution.Rejected r -> write(request, response, rejected(r));
            case Resolution.Resolved(ScopedTenantHolder.Bound bound) -> {
                SecurityContextHolder.getContext()
                        .setAuthentication(new TenantAuthentication(jwtAuth.getToken(), bound.permissions()));
                runBound(bound, request, response, chain);
            }
        }
    }

    /** The rest of the request, controllers included, runs inside the scoped binding. */
    private static void runBound(
            ScopedTenantHolder.Bound bound, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            ScopedValue.where(ScopedTenantHolder.CURRENT, bound).<Void, Exception>call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = path(request);
        return path.startsWith("/actuator/health") || path.equals("/actuator/info");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static ProblemDetail rejected(Resolution.Rejected r) {
        ProblemDetail problem = ProblemDetails.of(r.status(), r.code(), r.detail());
        if (r.fieldError() != null) {
            problem.setTitle("Invalid request");
            problem.setProperty("errors", List.of(r.fieldError()));
        }
        return problem;
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        problemWriter.write(request, response, problem);
    }
}
