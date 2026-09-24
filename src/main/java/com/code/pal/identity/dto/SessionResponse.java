package com.code.pal.identity.dto;

/**
 * Auth response body. The refresh token is deliberately absent — it travels in an HttpOnly
 * cookie the browser's JS can't read, so only the short-lived access token is handed to the SPA.
 */
public record SessionResponse(String accessToken, UserResponse user) {

    public static SessionResponse from(AuthResponse auth) {
        return new SessionResponse(auth.accessToken(), auth.user());
    }
}
