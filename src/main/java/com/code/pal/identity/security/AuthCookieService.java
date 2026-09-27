package com.code.pal.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Builds the refresh-token cookie.
 *
 * <p>{@code HttpOnly} keeps it out of reach of JavaScript (so an XSS can't steal it),
 * {@code SameSite=Lax} blocks it from riding cross-site requests (CSRF defence), and
 * {@code Path=/api/auth} means it's only ever sent to the refresh/logout endpoints — never
 * attached to ordinary API calls. {@code Secure} is enabled in production (HTTPS).
 */
@Service
public class AuthCookieService {

    public static final String REFRESH_COOKIE = "rt";

    private final Duration maxAge;
    private final boolean secure;

    public AuthCookieService(
            @Value("${jwt.refresh-expiry:604800000}") long refreshExpiryMs,
            @Value("${app.auth.cookie-secure}") boolean secure) {
        this.maxAge = Duration.ofMillis(refreshExpiryMs);
        this.secure = secure;
    }

    /** Cookie carrying the refresh token, scoped to the auth endpoints. */
    public ResponseCookie build(String refreshToken) {
        return base(refreshToken).maxAge(maxAge).build();
    }

    /** Expired cookie that clears the refresh token on logout. */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api/auth");
    }
}
