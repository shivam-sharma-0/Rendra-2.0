package com.code.pal.identity.dto;

/** Returned on signup / login / refresh. */
public record AuthResponse(String accessToken, String refreshToken, UserResponse user) {
}
