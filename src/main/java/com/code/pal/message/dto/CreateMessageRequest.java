package com.code.pal.message.dto;

import com.code.pal.project.domain.ProjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new user turn. Ids are client-generated (prefixed ULIDs) for idempotency and optimistic UI.
 *
 * @param id          the user message id (e.g. {@code umsg_…})
 * @param message     the prompt text
 * @param mode        the mode this turn runs in; falls back to the project default when null
 * @param aiMessageId optional id to reserve the assistant reply row up-front (e.g. {@code aimsg_…})
 * @param attachments optional project paths returned by the attachment upload ({@code uploads/…})
 */
public record CreateMessageRequest(
        @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+") String id,
        @NotBlank @Size(max = 32_000) String message,
        ProjectType mode,
        @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+") String aiMessageId,
        @Size(max = 6) java.util.List<@Size(max = 200) String> attachments
) {
    public CreateMessageRequest(String id, String message, ProjectType mode, String aiMessageId) {
        this(id, message, mode, aiMessageId, null);
    }
}
