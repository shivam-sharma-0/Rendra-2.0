package com.code.pal.workspace.dto;

import com.code.pal.workspace.domain.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

/** Change a member's role. OWNER is assigned only via ownership transfer, not here. */
public record UpdateMemberRoleRequest(
        @NotNull WorkspaceRole role
) {
}
