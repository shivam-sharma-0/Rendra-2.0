package com.code.pal.identity.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Application user (identity + credentials).
 *
 * <p>Indexes: unique index on {@code email} (login lookup + duplicate guard). The
 * table is intentionally lean — only columns read on the hot auth path — so a user
 * row stays small and cache-friendly.
 *
 * <p>{@link #publicId} is the only id ever exposed outside the service: the sequential
 * {@link #id} stays internal so external callers can't enumerate users or infer counts.
 */
@Entity
@Table(
        name = "users",
        indexes = @Index(name = "uq_users_email", columnList = "email", unique = true)
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    /** Stable, non-guessable external identifier — the only id shown to clients (JWT, API). */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    @Builder.Default
    UUID publicId = UUID.randomUUID();

    /** Login identifier. Unique + not-null; uniqueness enforced by the DB index above. */
    @Column(nullable = false, length = 255)
    String email;

    /** BCrypt hash — never the raw password. Null for federated (OAuth) accounts. */
    @Column(name = "password_hash", length = 60)
    String passwordHash;

    @Column(length = 100)
    String name;

    /** Credential owner: LOCAL (password) or an OAuth provider (e.g. GOOGLE). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    AuthProvider provider = AuthProvider.LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    Role role = Role.USER;

    /** Whether the email has been verified. */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    Boolean emailVerified = false;

    /** Soft ban / disable switch — an authentication check rejects disabled users. */
    @Column(nullable = false)
    @Builder.Default
    Boolean enabled = true;

    /** Last successful login — analytics + inactive-account cleanup. */
    @Column(name = "last_login_at")
    Instant lastLoginAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;
}
