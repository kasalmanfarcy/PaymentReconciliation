package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.token.RefreshCookies;
import com.reconflow.paymentreconflow.security.user.MembershipQueries.MembershipSummary;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ProblemDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth}: the only unauthenticated business endpoints. The request body of login is
 * never logged.
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

    record LoginResponse(String accessToken, long expiresIn, List<MembershipSummary> memberships) {}

    private final AuthService auth;
    private final RefreshCookies cookies;

    AuthController(AuthService auth, RefreshCookies cookies) {
        this.auth = auth;
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

    /**
     * Identical for an unknown email, a wrong password, a locked and a disabled account: status,
     * body and headers. Any difference would tell an attacker which emails have accounts.
     */
    static ResponseEntity<ProblemDetail> invalidCredentials() {
        ProblemDetail problem = ProblemDetails.of(
                HttpStatus.UNAUTHORIZED, ProblemCodes.INVALID_CREDENTIALS, "Invalid email or password.");
        problem.setTitle("Invalid email or password");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
