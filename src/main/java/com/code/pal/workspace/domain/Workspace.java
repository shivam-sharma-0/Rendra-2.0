package com.code.pal.workspace.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A container that owns projects — the scope every project and chat lives under
 * (mirrors {@code /workspaces/{id}/...}).
 *
 * <p>Every user gets one auto-provisioned {@link #personal} workspace on first use; teams can
 * later create shared ones. Access is decided by {@link WorkspaceMember} rows, not by
 * {@link #ownerId} alone — {@code ownerId} just records the creator.
 */
@Entity
@Table(
        name = "workspaces",
        indexes = @Index(name = "ix_workspaces_owner", columnList = "owner_id")
)
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(nullable = false, length = 120)
    String name;

    /** Creator (identity userId). Not the sole access gate — see {@link WorkspaceMember}. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    Long ownerId;

    /** True for the user's auto-created private workspace; false for shared/team ones. */
    @Column(nullable = false)
    @Builder.Default
    boolean personal = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    /** Soft-delete marker — null means active. */
    @Column(name = "deleted_at")
    Instant deletedAt;
}
