package com.code.pal.workspace.dto;

import com.code.pal.workspace.domain.WorkspaceInvite;
import com.code.pal.workspace.domain.WorkspaceRole;

import java.time.Instant;

/** The created invite — the client builds the shareable link from {@code token}. */
public record WorkspaceInviteResponse(
        String token,
        String workspaceName,
        WorkspaceRole role,
        Instant expiresAt
) {

    public static WorkspaceInviteResponse of(WorkspaceInvite invite, String workspaceName) {
        return new WorkspaceInviteResponse(invite.getToken(), workspaceName, invite.getRole(), invite.getExpiresAt());
    }
}
