package com.code.pal.project.service;

import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectRole;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.dto.CreateProjectRequest;
import com.code.pal.project.dto.ProjectMemberResponse;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.dto.UpdateProjectRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
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

    /** Advance a project lifecycle gate after a validated user action. */
    ProjectResponse advanceLifecycle(Long userId, UUID projectId, ProjectStage stage);

    ProjectResponse update(Long userId, UUID projectId, UpdateProjectRequest request);

    void delete(Long userId, UUID projectId);

    /**
     * Load a project the caller may access (workspace membership enforced), returning the managed
     * entity. Shared with the message module so access lives in one place.
     */
    Project requireAccessible(Long userId, UUID projectId);

    /** Load a project the caller may edit (workspace member, or a project OWNER/EDITOR collaborator). */
    Project requireCanEdit(Long userId, UUID projectId);

    /** Load a project whose code/state the caller may manage (owner or workspace admin). */
    Project requireManageable(Long userId, UUID projectId);

    /* ── Per-project sharing ─────────────────────────────────────────────────── */

    /** Collaborators shared on this project (caller must have access). */
    List<ProjectMemberResponse> listMembers(Long actorId, UUID projectId);

    /** Share the project with an existing account by email (caller must be able to manage it). */
    ProjectMemberResponse share(Long actorId, UUID projectId, String email, ProjectRole role);

    /** Change a collaborator's role (caller must be able to manage the project). */
    ProjectMemberResponse updateMemberRole(Long actorId, UUID projectId, UUID memberId, ProjectRole role);

    /** Revoke a collaborator's access (caller must be able to manage the project). */
    void removeMember(Long actorId, UUID projectId, UUID memberId);

    /* ── Publish lifecycle ───────────────────────────────────────────────────── */

    /** Publish the project: mark it LIVE and assign a stable public URL (caller must manage it). */
    ProjectResponse publish(Long actorId, UUID projectId);

    /** Take a published project offline (caller must manage it). Keeps the slug for re-publishing. */
    ProjectResponse unpublish(Long actorId, UUID projectId);

    /** Resolve a LIVE published project by slug for public serving; 404 if unknown or not live. */
    UUID resolvePublishedProject(String slug);
}
