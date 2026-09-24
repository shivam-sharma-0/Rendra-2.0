package com.code.pal.workspace.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Links a user to a workspace with a role — the single source of truth for "who can access
 * what". Every workspace-scoped read verifies a matching row exists for the caller.
 *
 * <p>Stored as plain ids ({@code workspaceId}, {@code userId}) rather than JPA relations to
 * keep the module boundary clean (no join into the users table). The unique index makes
 * membership lookups an O(log n) index probe and forbids duplicates.
 */
@Entity
@Table(
        name = "workspace_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_workspace_member", columnNames = {"workspace_id", "user_id"}),
        indexes = @Index(name = "ix_wsmember_user", columnList = "user_id")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    UUID workspaceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    WorkspaceRole role = WorkspaceRole.MEMBER;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
}
