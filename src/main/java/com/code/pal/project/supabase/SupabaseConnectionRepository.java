package com.code.pal.project.supabase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupabaseConnectionRepository extends JpaRepository<SupabaseConnection, UUID> {
    @org.springframework.data.jpa.repository.Query("select c.projectRef as projectRef, c.mode as mode, c.updatedAt as updatedAt from SupabaseConnection c where c.projectId = :projectId")
    java.util.Optional<Capability> capability(@org.springframework.data.repository.query.Param("projectId") UUID projectId);
    interface Capability {
        String getProjectRef();
        SupabaseConnection.Mode getMode();
        java.time.Instant getUpdatedAt();
    }
}
