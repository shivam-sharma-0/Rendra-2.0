package com.code.pal.workspace.dto;

import com.code.pal.identity.dto.UserSummary;
import com.code.pal.workspace.domain.WorkspaceMember;
import com.code.pal.workspace.domain.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

/**
 * A workspace member for the settings/members UI. The handle is the membership's own UUID
 * ({@code id}); the user is shown by {@code publicId} + email/name — never the internal user id.
 */
public record MemberResponse(
        UUID id,
        String userPublicId,
        String email,
        String name,
        WorkspaceRole role,
        Instant joinedAt
) {

    public static MemberResponse from(WorkspaceMember m, UserSummary user) {
        return new MemberResponse(
                m.getId(),
                user != null ? user.publicId().toString() : null,
                user != null ? user.email() : null,
                user != null ? user.name() : null,
                m.getRole(),
                m.getCreatedAt());
    }
}
