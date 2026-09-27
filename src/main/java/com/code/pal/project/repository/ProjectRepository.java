package com.code.pal.project.repository;

import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Project persistence. Listing is keyed on {@code workspace_id} and rides the
 * {@code (workspace_id, last_activity_at)} index. {@code findById} (inherited) plus a workspace
 * membership check is how single projects are authorized — see the service.
 * The {@code @SQLRestriction} on the entity appends {@code deleted_at IS NULL} to every query.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** A workspace's projects — pair with a sort on {@code lastActivityAt} to use the index. */
    Page<Project> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    /**
     * A workspace's projects of the given types (Chats = CHAT; Projects = BUILD, PLAN). Rides the
     * {@code (workspace_id, type, last_activity_at)} index.
     */
    Page<Project> findByWorkspaceIdAndTypeIn(UUID workspaceId, Collection<ProjectType> types, Pageable pageable);

    /** Resolve a published project by its public slug (for the public {@code /p/{slug}} serving). */
    Optional<Project> findByPublicSlug(String publicSlug);
}
