package com.code.pal.project.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Grants a specific user access to a single project with a {@link ProjectRole} — the model behind
 * "Share this project". Independent of workspace membership, so you can share one project with
 * someone who isn't in the workspace. Stored as plain ids to keep the module boundary clean.
 */
@Entity
@Table(
        name = "project_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_project_member", columnNames = {"project_id", "user_id"}),
        indexes = @Index(name = "ix_projectmember_user", columnList = "user_id")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ProjectMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    UUID projectId;

    @Column(name = "user_id", nullable = false, updatable = false)
    Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    ProjectRole role = ProjectRole.VIEWER;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
}
