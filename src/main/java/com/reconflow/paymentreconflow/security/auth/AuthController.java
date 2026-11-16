package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.auth.AuthService.ChangeResult;
import com.reconflow.paymentreconflow.security.token.RefreshCookies;
import com.reconflow.paymentreconflow.security.token.RefreshService;
import com.reconflow.paymentreconflow.security.user.MembershipQueries.MembershipSummary;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ProblemDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth}: login, refresh, logout, change-password. Request bodies here are never
 * logged. Refresh and logout read the refresh token from the cookie only, never from a body or a
 * header, so script running in the page can't supply (or have stolen) it.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(@NotBlank String email, @NotBlank String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    record LoginResponse(String accessToken, long expiresIn, List<MembershipSummary> memberships) {}

    record TokenResponse(String accessToken, long expiresIn) {}

    private final AuthService auth;
    private final RefreshService refresh;
    private final RefreshCookies cookies;

    AuthController(AuthService auth, RefreshService refresh, RefreshCookies cookies) {
        this.auth = auth;
        this.refresh = refresh;
        this.cookies = cookies;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return switch (auth.login(request.email(), request.password())) {
            case LoginResult.Success s ->
                ResponseEntity.ok()
                        .header(
                                HttpHeaders.SET_COOKIE,
                                cookies.issue(s.refreshToken()).toString())
                        .body(new LoginResponse(
                                s.accessToken().value(), s.accessToken().expiresIn(), s.memberships()));
            case LoginResult.Failure f -> invalidCredentials();
        };
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = RefreshCookies.NAME, required = false) @Nullable String token) {
        if (token == null || token.isBlank()) {
            return unauthenticated();
        }
        return refresh.refresh(token)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok()
                        .header(
                                HttpHeaders.SET_COOKIE,
                                cookies.issue(r.refreshToken()).toString())
                        .body(new TokenResponse(
                                r.accessToken().value(), r.accessToken().expiresIn())))
                .orElseGet(this::unauthenticated);
    }

    /** Always 204 with an expired cookie, whether or not the token was valid: logout is idempotent. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = RefreshCookies.NAME, required = false) @Nullable String token) {
        if (token != null && !token.isBlank()) {
            refresh.logout(token);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    @PostMapping(path = "/change-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return switch (auth.changePassword(userId, request.currentPassword(), request.newPassword())) {
            case ChangeResult.Changed c ->
                ResponseEntity.ok()
                        .header(
                                HttpHeaders.SET_COOKIE,
                                cookies.issue(c.refreshToken()).toString())
                        .body(new TokenResponse(
                                c.accessToken().value(), c.accessToken().expiresIn()));
            case ChangeResult.WrongPassword w -> invalidCredentials();
        };
    }

    /**
     * Identical for an unknown email, a wrong password, a locked and a disabled account: status,
     * body and headers. Any difference would tell an attacker which emails have accounts.
     */
    static ResponseEntity<ProblemDetail> invalidCredentials() {
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.UNAUTHORIZED, ProblemCodes.INVALID_CREDENTIALS, "Invalid email or password.");
        problem.setTitle("Invalid email or password");
        return problem(problem);
    }

    /** A refresh that failed for any reason; the dead cookie is cleared so the client stops sending it. */
    private ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetails.of(
                        HttpStatus.UNAUTHORIZED,
                        ProblemCodes.UNAUTHENTICATED,
                        "The session has expired. Log in again."));
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
