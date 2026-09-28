package com.code.pal.project.repository;

import com.code.pal.project.domain.RequirementSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RequirementSessionRepository extends JpaRepository<RequirementSession, UUID> {
    Optional<RequirementSession> findByProjectId(UUID projectId);
}
