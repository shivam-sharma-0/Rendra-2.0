package com.code.pal.workspace.dto;

import com.code.pal.workspace.domain.WorkspaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Create a shareable invite link. Email is optional (restricts who can accept); role defaults to MEMBER. */
public record CreateInviteRequest(
        @Email @Size(max = 255) String email,
        WorkspaceRole role
) {
}
