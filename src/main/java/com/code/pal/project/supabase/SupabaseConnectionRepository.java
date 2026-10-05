package com.code.pal.project.supabase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupabaseConnectionRepository extends JpaRepository<SupabaseConnection, UUID> {
}
