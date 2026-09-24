package com.code.pal.identity.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.Base64;

/**
 * Stores the in-flight {@link OAuth2AuthorizationRequest} in a short-lived, HttpOnly
 * cookie instead of an HTTP session — so the whole app stays session-less (the API's
 * core guarantee) even during the OAuth handshake.
 *
 * <p>The cookie exists only for the few seconds between redirecting to Google and the
 * callback, is {@code SameSite=Lax} (survives the provider's top-level GET redirect),
 * and is deleted the moment it is consumed.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "cp_oauth2_auth_request";
    private static final int MAX_AGE_SECONDS = 180;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookie(request).map(HttpCookieOAuth2AuthorizationRequestRepository::deserialize).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(response);
            return;
        }
        ResponseCookie cookie = baseCookie(serialize(authorizationRequest), MAX_AGE_SECONDS);
        response.addHeader("Set-Cookie", cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authRequest = loadAuthorizationRequest(request);
        if (authRequest != null) {
            deleteCookie(response);
        }
        return authRequest;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static java.util.Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return java.util.Optional.empty();
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) return java.util.Optional.of(c.getValue());
        }
        return java.util.Optional.empty();
    }

    private static ResponseCookie baseCookie(String value, int maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(false)          // dev over http; flip to true behind TLS
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }

    private void deleteCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", baseCookie("", 0).toString());
    }

    private static String serialize(OAuth2AuthorizationRequest obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return Base64.getUrlEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize authorization request", e);
        }
    }

    private static OAuth2AuthorizationRequest deserialize(String value) {
        try {
            byte[] data = Base64.getUrlDecoder().decode(value);
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
                return (OAuth2AuthorizationRequest) ois.readObject();
            }
        } catch (Exception e) {
            return null;   // tampered / stale cookie → treat as absent
        }
    }
}
