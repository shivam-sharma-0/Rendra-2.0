package com.code.pal.identity.domain;

/**
 * Coarse-grained authorization role, stored as a single column on the user row.
 *
 * <p>Kept as an enum column (not a join table) deliberately: one user has exactly
 * one role, so this avoids an extra table, an extra join on every auth lookup, and
 * the associated memory — the cheapest representation for the common case. The Spring
 * Security {@code ROLE_} prefix is added when building authorities, not stored here.
 */
public enum Role {
    USER,
    ADMIN
}
