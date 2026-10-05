package com.code.pal.project.event;

import java.util.UUID;

/**
 * Published (within the deleting transaction) when a project is soft-deleted. The agent module listens
 * {@code AFTER_COMMIT} to stop the project's runs; its files and sandbox are removed by a sweep.
 */
public record ProjectDeleted(UUID projectId) {
}
