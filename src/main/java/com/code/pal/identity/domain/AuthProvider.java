package com.code.pal.identity.domain;

/**
 * How a user authenticates. {@code LOCAL} users have a BCrypt password hash;
 * federated users (e.g. {@code GOOGLE}) have none and sign in via OAuth only.
 *
 * <p>Stored as a single enum column on the user row — no join, no extra lookup on
 * the auth path — mirroring the {@link Role} design.
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE
}
