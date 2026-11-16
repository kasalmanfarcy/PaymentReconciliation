package com.reconflow.paymentreconflow.security.token;

import com.reconflow.paymentreconflow.security.config.AuthProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh-token cookie (authentication.plan §3): {@code HttpOnly} so script (and so XSS)
 * can't read it, {@code SameSite=Strict} plus the narrow {@code Path} so the browser only sends it
 * to the auth endpoints of the same site, and {@code Secure} unless the local profile turns it off.
 *
 * <p>Assumes the SPA and API share a registrable domain; cross-site, Strict means the cookie is
 * never sent, and the design would need SameSite=None plus CSRF tokens on refresh and logout.
 */
@Component
public class RefreshCookies {

    public static final String NAME = "refresh_token";
    public static final String PATH = "/api/v1/auth";

    private final AuthProperties properties;

    RefreshCookies(AuthProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie issue(String rawToken) {
        return base(rawToken).maxAge(properties.refresh().tokenTtl()).build();
    }

    /** Max-Age=0 tells the browser to delete it. */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .sameSite("Strict")
                .path(PATH);
    }
}
