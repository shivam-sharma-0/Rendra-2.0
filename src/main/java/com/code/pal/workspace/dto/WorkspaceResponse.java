package com.code.pal.workspace.dto;

import com.code.pal.workspace.domain.Workspace;
import com.code.pal.workspace.domain.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

/** Public view of a workspace, carrying the caller's role in it. */
public record WorkspaceResponse(
        UUID id,
        String name,
        boolean personal,
        WorkspaceRole role,
        Instant createdAt
) {

    public static WorkspaceResponse from(Workspace w, WorkspaceRole role) {
        return new WorkspaceResponse(w.getId(), w.getName(), w.isPersonal(), role, w.getCreatedAt());
    }
}
