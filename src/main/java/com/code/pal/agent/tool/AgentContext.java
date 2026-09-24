package com.code.pal.agent.tool;

import com.code.pal.project.domain.ProjectType;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Everything a tool needs to act, for one run: which project (and its on-disk workspace),
 * who triggered it, and the working mode. Passed to every {@link AgentTool#execute}.
 *
 * @param projectId the project being worked on
 * @param userId    the user who triggered the run (for auditing / permission-scoped tools)
 * @param mode      BUILD / CHAT / PLAN
 * @param workspace absolute path to this project's code directory
 */
public record AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace) {

    /** Resolve a relative path inside the workspace, rejecting anything that escapes it. */
    public Path resolveSafe(String relativePath) {
        Path resolved = workspace.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("Path escapes the project workspace: " + relativePath);
        }
        return resolved;
    }
}
