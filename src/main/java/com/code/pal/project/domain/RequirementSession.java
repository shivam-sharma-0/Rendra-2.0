package com.code.pal.project.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "requirement_sessions", indexes = @Index(name = "ix_requirement_sessions_project", columnList = "project_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RequirementSession {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "project_id", nullable = false, unique = true) UUID projectId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) @Builder.Default RequirementStatus status = RequirementStatus.DRAFT;
    @Column(name = "source_prompt", nullable = false, columnDefinition = "text") String sourcePrompt;
    @Column(name = "approved_at") Instant approvedAt;
    @Column(name = "approved_by") Long approvedBy;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
