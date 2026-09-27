package com.code.pal.agent.tool;

import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;

import java.time.Instant;
import java.util.UUID;

/** Safe project fields made available to tools after the runtime authorizes the caller. */
public record AgentProjectMetadata(
        UUID projectId,
        UUID workspaceId,
        String name,
        ProjectType type,
        boolean isPublic,
        DeploymentStatus deploymentStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentProjectMetadata from(Project project) {
        return new AgentProjectMetadata(project.getId(), project.getWorkspaceId(), project.getName(),
                project.getType(), Boolean.TRUE.equals(project.getIsPublic()), project.getDeploymentStatus(),
                project.getCreatedAt(), project.getUpdatedAt());
    }
}
