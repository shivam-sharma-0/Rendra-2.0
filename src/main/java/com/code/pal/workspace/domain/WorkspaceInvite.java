package com.code.pal.workspace.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A shareable invite link to join a workspace. The random {@code token} is the credential in the URL;
 * an optional {@code email} restricts who may accept it. Accepting creates a {@link WorkspaceMember}.
 */
@Entity
@Table(
        name = "workspace_invites",
        uniqueConstraints = @UniqueConstraint(name = "uq_invite_token", columnNames = "token"),
        indexes = @Index(name = "ix_invite_workspace", columnList = "workspace_id")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class WorkspaceInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false, length = 64, updatable = false)
    String token;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    UUID workspaceId;

    /** Optional — when set, only this email may accept the invite. */
    @Column(length = 255, updatable = false)
    String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    @Builder.Default
    WorkspaceRole role = WorkspaceRole.MEMBER;

    @Column(name = "invited_by_user_id", nullable = false, updatable = false)
    Long invitedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    InviteStatus status = InviteStatus.PENDING;

    @Column(name = "accepted_by_user_id")
    Long acceptedByUserId;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
}
