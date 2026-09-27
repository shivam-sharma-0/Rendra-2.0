package com.code.pal.project.dto;

import com.code.pal.identity.dto.UserSummary;
import com.code.pal.project.domain.ProjectMember;
import com.code.pal.project.domain.ProjectRole;

import java.time.Instant;
import java.util.UUID;

/** A project collaborator for the Share panel. The handle is the membership UUID; the user is shown by publicId + email/name. */
public record ProjectMemberResponse(
        UUID id,
        String userPublicId,
        String email,
        String name,
        ProjectRole role,
        Instant sharedAt
) {

    public static ProjectMemberResponse from(ProjectMember m, UserSummary user) {
        return new ProjectMemberResponse(
                m.getId(),
                user != null ? user.publicId().toString() : null,
                user != null ? user.email() : null,
                user != null ? user.name() : null,
                m.getRole(),
                m.getCreatedAt());
    }
}
