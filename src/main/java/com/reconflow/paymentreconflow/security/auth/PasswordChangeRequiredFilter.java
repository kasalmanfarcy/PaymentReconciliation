package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.token.AccessTokenService;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ProblemDetailWriter;
import com.reconflow.paymentreconflow.shared.error.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While a temporary password is pending (the access token carries {@code pwd_change: true}),
 * every route except change-password and logout answers 403 {@code password-change-required}.
 *
 * <p>Runs right after bearer authentication and before tenant resolution: a user who must change
 * their password gets no data, and costs no membership query. Not a bean, so Boot doesn't also
 * register it as a plain servlet filter outside the security chain.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private final ProblemDetailWriter problemWriter;

    public PasswordChangeRequiredFilter(ProblemDetailWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (passwordChangePending() && !allowedWhilePending(request)) {
            problemWriter.write(
                    request,
                    response,
                    ProblemDetails.of(
                            HttpStatus.FORBIDDEN,
                            ProblemCodes.PASSWORD_CHANGE_REQUIRED,
                            "Change your temporary password before doing anything else."));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean passwordChangePending() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof Jwt jwt
                && Boolean.TRUE.equals(jwt.getClaimAsBoolean(AccessTokenService.PASSWORD_CHANGE_CLAIM));
    }

    private static boolean allowedWhilePending(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "POST".equals(request.getMethod())
                && (path.equals("/api/v1/auth/change-password") || path.equals("/api/v1/auth/logout"));
    }
}
