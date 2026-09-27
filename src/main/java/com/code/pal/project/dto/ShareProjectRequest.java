package com.code.pal.project.dto;

import com.code.pal.project.domain.ProjectRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Share a project with someone by email at a given role (defaults to VIEWER when omitted). */
public record ShareProjectRequest(
        @NotBlank @Email @Size(max = 255) String email,
        ProjectRole role
) {
}
