package com.code.pal.project.event;

import com.code.pal.project.domain.ProjectType;

import java.util.UUID;

/**
 * Published (within the posting transaction) when a user turn needs an assistant reply. The agent
 * module persists its run/outbox BEFORE_COMMIT in the same transaction, then attempts
 * queue delivery AFTER_COMMIT. A durable poller retries after a crash or queue outage.
 *
 * <p>Lives in the project module (not agent) so message/project can publish it without depending on
 * the agent module — keeping the dependency arrow one-way and the future service split clean.
 *
 * @param projectId          the project (room) the reply belongs to
 * @param userId             the user who triggered the turn
 * @param mode               working mode for this turn
 * @param assistantMessageId the placeholder assistant message the run will fill (client ULID)
 */
public record AssistantTurnRequested(UUID projectId, Long userId, ProjectType mode, String assistantMessageId) {
}
