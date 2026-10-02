package com.code.pal.identity.security;

import com.code.pal.identity.domain.User;
import com.code.pal.shared.security.AuthPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies JWTs using RS256 (asymmetric).
 *
 * <p>Signing uses the private key (held only here); verification uses the public key,
 * which can be handed to any other service. Keys are injected once at startup, so each
 * token op is just a signature over the payload — no per-call key work.
 */
@Slf4j
@Service
public class JwtService {

    /** Marks an access token vs a refresh token (the {@code typ} claim). */
    public static final String ACCESS = "ACCESS";
    public static final String REFRESH = "REFRESH";
    public static final String PREVIEW = "PREVIEW";

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_USER_ID = "uid";
    private static final String CLAIM_PUBLIC_ID = "pub";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PROJECT_ID = "pid";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;
    private final long previewExpiryMs;

    public JwtService(
            RSAPrivateKey jwtPrivateKey,
            RSAPublicKey jwtPublicKey,
            @Value("${jwt.access-expiry:900000}") long accessExpiryMs,
            @Value("${jwt.refresh-expiry:604800000}") long refreshExpiryMs,
            @Value("${jwt.preview-expiry:1800000}") long previewExpiryMs) {
        this.privateKey = jwtPrivateKey;
        this.publicKey = jwtPublicKey;
        this.accessExpiryMs = accessExpiryMs;
        this.refreshExpiryMs = refreshExpiryMs;
        this.previewExpiryMs = previewExpiryMs;
    }

    /** Short-lived token carrying identity + role, used to authorize API calls. */
    public String generateAccessToken(User user) {
        return build(user, ACCESS, accessExpiryMs);
    }

    /** Long-lived token used only to mint new access tokens at {@code /api/auth/refresh}. */
    public String generateRefreshToken(User user) {
        return build(user, REFRESH, refreshExpiryMs);
    }

    /** Short-lived bearer-less URL token used only by an authenticated project's preview iframe. */
    public String generatePreviewToken(java.util.UUID projectId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(projectId.toString())
                .claim(CLAIM_TYPE, PREVIEW)
                .claim(CLAIM_PROJECT_ID, projectId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(previewExpiryMs)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private String build(User user, String type, long ttlMs) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_PUBLIC_ID, user.getPublicId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMs)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** Parses and verifies signature + expiry in one pass; throws {@link io.jsonwebtoken.JwtException} if invalid. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public AuthPrincipal toPrincipal(Claims claims) {
        return new AuthPrincipal(
                claims.get(CLAIM_USER_ID, Long.class),
                java.util.UUID.fromString(claims.get(CLAIM_PUBLIC_ID, String.class)),
                claims.getSubject(),
                claims.get(CLAIM_ROLE, String.class));
    }

    public String tokenType(Claims claims) {
        return claims.get(CLAIM_TYPE, String.class);
    }

    public java.util.UUID previewProjectId(Claims claims) {
        if (!PREVIEW.equals(tokenType(claims))) throw new IllegalArgumentException("Not a preview token");
        return java.util.UUID.fromString(claims.get(CLAIM_PROJECT_ID, String.class));
    }
}
