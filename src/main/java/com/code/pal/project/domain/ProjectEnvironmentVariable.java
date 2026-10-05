package com.code.pal.project.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A build-time variable (VITE_*) of a generated project; baked into the built site, so not a secret. */
@Entity
@Table(name = "project_environment_variables",
        uniqueConstraints = @UniqueConstraint(name = "uq_project_environment_variables_name", columnNames = {"project_id", "name"}),
        indexes = @Index(name = "ix_project_environment_variables_project", columnList = "project_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ProjectEnvironmentVariable {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "project_id", nullable = false) UUID projectId;
    @Column(nullable = false, length = 64) String name;
    @Column(nullable = false, length = 4000) String value;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
