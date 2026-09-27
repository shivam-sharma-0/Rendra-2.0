package com.code.pal.workspace.dto;

import com.code.pal.workspace.domain.WorkspaceRole;

/** What the accept-invite screen shows before the user accepts. {@code valid} is false when expired/used/revoked. */
public record InvitePreviewResponse(
        String workspaceName,
        String invitedByName,
        WorkspaceRole role,
        boolean valid,
        String reason
) {
}
