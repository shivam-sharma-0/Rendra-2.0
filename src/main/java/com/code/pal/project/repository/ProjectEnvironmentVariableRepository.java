package com.code.pal.project.repository;

import com.code.pal.project.domain.ProjectEnvironmentVariable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectEnvironmentVariableRepository extends JpaRepository<ProjectEnvironmentVariable, UUID> {
    List<ProjectEnvironmentVariable> findByProjectIdOrderByNameAsc(UUID projectId);

    Optional<ProjectEnvironmentVariable> findByProjectIdAndName(UUID projectId, String name);

    long countByProjectId(UUID projectId);
}
