package com.code.pal.agent.change;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit record for one staged mutation. Hashes identify both sides without storing source in the DB. */
@Entity
@Table(name = "agent_file_changes", indexes = @Index(name = "ix_agent_file_changes_run_created", columnList = "run_id, created_at"))
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class FileChange {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "run_id", nullable = false, updatable = false) UUID runId;
    @Column(name = "project_id", nullable = false, updatable = false) UUID projectId;
    @Column(nullable = false, length = 1024) String path;
    @Column(name = "source_path", length = 1024) String sourcePath;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) FileOperation operation;
    @Column(name = "before_version", nullable = false, length = 80) String beforeVersion;
    @Column(name = "after_version", nullable = false, length = 80) String afterVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
}
