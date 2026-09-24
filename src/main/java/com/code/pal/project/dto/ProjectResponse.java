package com.code.pal.project.dto;

import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;

import java.time.Instant;
import java.util.UUID;

/** Public view of a project. Excludes the soft-delete marker — it is an internal detail. */
public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String name,
        String initialPrompt,
        ProjectType type,
        Boolean isPublic,
        String publishedUrl,
        DeploymentStatus deploymentStatus,
        Instant lastActivityAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.getId(),
                p.getWorkspaceId(),
                p.getName(),
                p.getInitialPrompt(),
                p.getType(),
                p.getIsPublic(),
                p.getPublishedUrl(),
                p.getDeploymentStatus(),
                p.getLastActivityAt(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
