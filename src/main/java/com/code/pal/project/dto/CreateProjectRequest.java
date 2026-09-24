package com.code.pal.project.dto;

import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.project.domain.ProjectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Create payload. {@code ownerId}/{@code workspaceId} are never taken from the body — the owner
 * is the authenticated principal and the workspace comes from the path.
 *
 * <p>{@code name} is optional; when blank it is derived from the first prompt. An optional
 * {@code initialMessage} seeds the conversation in the same call (Lovable-style), so a project
 * and its first turn are created atomically.
 */
public record CreateProjectRequest(
        @Size(max = 120) String name,
        ProjectType type,
        @Valid CreateMessageRequest initialMessage
) {
}
