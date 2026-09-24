package com.code.pal.project.service;

import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.dto.CreateProjectRequest;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.dto.UpdateProjectRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.UUID;

/**
 * Project use-cases. Every method authorizes the caller against the project's workspace
 * (membership); a non-member or missing id is indistinguishable (404/403).
 */
public interface ProjectService {

    ProjectResponse create(Long userId, UUID workspaceId, CreateProjectRequest request);

    /** List a workspace's projects, optionally filtered to the given types (null/empty = all). */
    Page<ProjectResponse> list(Long userId, UUID workspaceId, Collection<ProjectType> types, Pageable pageable);

    ProjectResponse get(Long userId, UUID projectId);

    ProjectResponse update(Long userId, UUID projectId, UpdateProjectRequest request);

    void delete(Long userId, UUID projectId);

    /**
     * Load a project the caller may access (workspace membership enforced), returning the managed
     * entity. Shared with the message module so access lives in one place.
     */
    Project requireAccessible(Long userId, UUID projectId);
}
