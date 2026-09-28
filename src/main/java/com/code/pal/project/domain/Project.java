package com.code.pal.project.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A project — the unit an agent builds, chats about, or plans.
 *
 * <p><b>Scoping</b>: a project belongs to a {@code workspaceId}; access is decided by the
 * caller's membership in that workspace (not by {@code ownerId}, which only records the
 * creator). Both are plain ids, not JPA relations — the module never joins across boundaries.
 *
 * <p><b>Soft delete</b>: {@link #deletedAt} marks a row dead; {@code @SQLRestriction} then
 * hides it from every query automatically, so callers never need to repeat the filter.
 *
 * <p><b>Indexing</b>: the composite {@code (workspace_id, last_activity_at)} index turns the
 * hot "workspace projects, most-recently-active first" listing into a single range scan.
 */
@Entity
@Table(
        name = "projects",
        indexes = {
                // "all projects, most-recent-first" listing.
                @Index(name = "ix_projects_workspace_activity", columnList = "workspace_id, last_activity_at"),
                // Type-filtered listing (Chats vs Projects) — keeps that query on a single range scan.
                @Index(name = "ix_projects_workspace_type_activity", columnList = "workspace_id, type, last_activity_at")
        }
)
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    /** Owning workspace — the scope every access check runs against. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    UUID workspaceId;

    @Column(nullable = false, length = 120)
    String name;

    /** Creator (identity userId). Records who made it; not the access gate — membership is. */
    @Column(name = "owner_id", nullable = false, updatable = false)
    Long ownerId;

    /** The first prompt that seeded the project — powers the card subtitle and auto-title. */
    @Column(name = "initial_prompt", columnDefinition = "text")
    String initialPrompt;

    /** Working mode: BUILD | CHAT | PLAN. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    ProjectType type = ProjectType.BUILD;

    /** Explicit gate between prompt intake, design, build, review and deployment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_stage", nullable = false, length = 20)
    @ColumnDefault("'DESIGN'")
    @Builder.Default
    ProjectStage lifecycleStage = ProjectStage.REQUIREMENTS;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    Boolean isPublic = false;

    /** Public URL of the live deployment — set when a deploy reports LIVE. Null until then. */
    @Column(name = "published_url", length = 512)
    String publishedUrl;

    /** Stable public slug used in the published URL ({@code /p/{slug}}). Assigned on first publish. */
    @Column(name = "public_slug", length = 64, unique = true)
    String publicSlug;

    /** Mirrors the deployment lifecycle. */
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_status", nullable = false, length = 16)
    @Builder.Default
    DeploymentStatus deploymentStatus = DeploymentStatus.NONE;

    /** Last time the project saw activity (new message / edit) — the dashboard sort key. */
    @Column(name = "last_activity_at", nullable = false)
    @Builder.Default
    Instant lastActivityAt = Instant.now();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    /** Soft-delete marker — null means the project is active. */
    @Column(name = "deleted_at")
    Instant deletedAt;
}
