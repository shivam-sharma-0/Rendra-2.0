package com.code.pal.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create a shared (team) workspace. The personal one is provisioned automatically, not here. */
public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 120) String name
) {
}
