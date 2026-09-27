package com.code.pal.identity.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stores the in-flight {@link OAuth2AuthorizationRequest} server-side in Redis. The browser gets
 * only a random, short-lived opaque state handle, so it never supplies serialized Java objects.
 *
 * <p>The cookie exists only for the few seconds between redirecting to Google and the
 * callback, is {@code SameSite=Lax} (survives the provider's top-level GET redirect), and is
 * deleted the moment it is consumed.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "cp_oauth2_state";
    private static final String KEY_PREFIX = "oauth2:authorization-request:";
    private static final int MAX_AGE_SECONDS = 180;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean secure;

    public HttpCookieOAuth2AuthorizationRequestRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${app.auth.cookie-secure}") boolean secure) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.secure = secure;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return readCookie(request)
                .flatMap(this::load)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(response);
            return;
        }
        String stateId = newStateId();
        store(stateId, authorizationRequest);
        ResponseCookie cookie = baseCookie(stateId, MAX_AGE_SECONDS);
        response.addHeader("Set-Cookie", cookie.toString());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String stateId = readCookie(request).orElse(null);
        OAuth2AuthorizationRequest authRequest = stateId == null ? null : load(stateId).orElse(null);
        if (stateId != null) redis.delete(redisKey(stateId));
        deleteCookie(response);
        return authRequest;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return java.util.Optional.empty();
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) return java.util.Optional.of(c.getValue());
        }
        return java.util.Optional.empty();
    }

    private ResponseCookie baseCookie(String value, int maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }

    private void deleteCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", baseCookie("", 0).toString());
    }

    private void store(String stateId, OAuth2AuthorizationRequest request) {
        try {
            redis.opsForValue().set(
                    redisKey(stateId),
                    objectMapper.writeValueAsString(StoredAuthorizationRequest.from(request)),
                    Duration.ofSeconds(MAX_AGE_SECONDS));
        } catch (Exception e) {
            throw new IllegalStateException("Could not store OAuth2 authorization request", e);
        }
    }

    private Optional<OAuth2AuthorizationRequest> load(String stateId) {
        try {
            String json = redis.opsForValue().get(redisKey(stateId));
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, StoredAuthorizationRequest.class).toRequest());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String newStateId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String redisKey(String stateId) {
        return KEY_PREFIX + stateId;
    }

    private record StoredAuthorizationRequest(
            String authorizationUri,
            String clientId,
            String redirectUri,
            Set<String> scopes,
            String state,
            Map<String, Object> additionalParameters,
            Map<String, Object> attributes) {

        static StoredAuthorizationRequest from(OAuth2AuthorizationRequest request) {
            return new StoredAuthorizationRequest(
                    request.getAuthorizationUri(), request.getClientId(), request.getRedirectUri(),
                    request.getScopes(), request.getState(), request.getAdditionalParameters(), request.getAttributes());
        }

        OAuth2AuthorizationRequest toRequest() {
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(authorizationUri)
                    .clientId(clientId)
                    .redirectUri(redirectUri)
                    .scopes(scopes == null ? Set.of() : scopes)
                    .state(state)
                    .additionalParameters(additionalParameters == null ? Map.of() : additionalParameters)
                    .attributes(attributes == null ? Map.of() : attributes)
                    .build();
        }
    }
}
