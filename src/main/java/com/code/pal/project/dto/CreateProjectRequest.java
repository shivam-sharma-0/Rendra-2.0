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
 *
 * <p>{@code pendingPrompt} instead names the first prompt when it is posted separately, right after its
 * attachments are uploaded into the new project: it decides the name and whether the project starts with
 * requirements, exactly as {@code initialMessage} would, but seeds nothing.
 */
public record CreateProjectRequest(
        @Size(max = 120) String name,
        ProjectType type,
        @Valid CreateMessageRequest initialMessage,
        @Size(max = 32_000) String pendingPrompt
) {
    public CreateProjectRequest(String name, ProjectType type, CreateMessageRequest initialMessage) {
        this(name, type, initialMessage, null);
    }
}
