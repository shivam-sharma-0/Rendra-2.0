package com.code.pal.project.dto;

import com.code.pal.project.domain.ProjectType;
import jakarta.validation.constraints.Size;

/**
 * Partial update — every field is optional; a {@code null} means "leave unchanged".
 * Only fields a user is allowed to change are here; {@code deploymentStatus} /
 * {@code publishedUrl} are driven by the deploy pipeline, not the client.
 */
public record UpdateProjectRequest(
        @Size(min = 1, max = 120) String name,
        Boolean isPublic,
        ProjectType type
) {
}
