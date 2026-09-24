package com.code.pal.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Update workspace profile (rename). */
public record UpdateWorkspaceRequest(
        @NotBlank @Size(max = 120) String name
) {
}
