package com.code.pal.project.supabase;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.UUID;

/** A migration file the user approved and the backend applied to their Supabase database. */
@Entity
@Table(name = "project_supabase_migrations",
        uniqueConstraints = @UniqueConstraint(name = "uq_project_supabase_migrations_name", columnNames = {"project_id", "name"}),
        indexes = @Index(name = "ix_project_supabase_migrations_project", columnList = "project_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SupabaseMigration {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "project_id", nullable = false) UUID projectId;
    @Column(nullable = false, length = 200) String name;
    @Column(nullable = false, length = 80) String checksum;
    @Column(name = "applied_by", nullable = false) Long appliedBy;
    @Column(name = "applied_at", nullable = false) Instant appliedAt;
}
