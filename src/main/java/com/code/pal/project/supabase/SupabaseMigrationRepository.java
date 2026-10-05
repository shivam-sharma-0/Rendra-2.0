package com.code.pal.project.supabase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupabaseMigrationRepository extends JpaRepository<SupabaseMigration, UUID> {
    List<SupabaseMigration> findByProjectIdOrderByNameAsc(UUID projectId);

    void deleteByProjectId(UUID projectId);
}
