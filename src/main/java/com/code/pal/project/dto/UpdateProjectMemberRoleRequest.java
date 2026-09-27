package com.code.pal.project.dto;

import com.code.pal.project.domain.ProjectRole;
import jakarta.validation.constraints.NotNull;

/** Change a project collaborator's role. */
public record UpdateProjectMemberRoleRequest(
        @NotNull ProjectRole role
) {
}
