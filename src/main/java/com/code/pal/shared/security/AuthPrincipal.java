package com.code.pal.shared.security;

/**
 * Immutable authenticated principal placed in the SecurityContext.
 *
 * <p>Lives in {@code shared} because every module's controllers read it via
 * {@code @AuthenticationPrincipal}. Built purely from verified JWT claims — never a DB
 * entity — so nothing is loaded per request.
 *
 * @param userId   the authenticated user's internal id (scoping key; never exposed to clients)
 * @param publicId the user's external UUID (safe to echo in API responses)
 * @param email    the authenticated user's email (JWT subject)
 * @param role     the user's role name (without the {@code ROLE_} prefix)
 */
public record AuthPrincipal(Long userId, java.util.UUID publicId, String email, String role) {
}
