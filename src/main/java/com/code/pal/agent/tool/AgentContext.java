package com.code.pal.agent.tool;

import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.project.domain.ProjectType;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Context passed to a tool for a single agent run. */
public record AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                           AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace,
                           UUID runId, AgentPlanState plan) {

    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                        AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace) {
        this(projectId, userId, mode, workspace, projectMetadata, contextTrace,
                UUID.randomUUID(), new AgentPlanState());
    }

    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                        AgentProjectMetadata projectMetadata) {
        this(projectId, userId, mode, workspace, projectMetadata, new ProjectContextTrace(),
                UUID.randomUUID(), new AgentPlanState());
    }

    /** Compatibility constructor for isolated tool tests and non-runtime callers. */
    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace) {
        this(projectId, userId, mode, workspace, null, new ProjectContextTrace(),
                UUID.randomUUID(), new AgentPlanState());
    }

    /** Directory names the agent may never traverse into (repo internals / host credentials). */
    private static final Set<String> BLOCKED_NAMES = Set.of(".git", ".ssh", ".secrets");

    /** Resolve a relative non-symlink path inside this project workspace. */
    public Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Project path must not be blank");
        }
        Path requested = Path.of(relativePath);
        Path root = workspace.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Project workspace cannot be a symbolic link");
        }
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException("Absolute project paths are not allowed: " + relativePath);
        }
        for (Path part : requested) {
            if (part.toString().equals("..")) {
                throw new IllegalArgumentException("Parent path traversal is not allowed: " + relativePath);
            }
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the project workspace: " + relativePath);
        }
        // Walk the NORMALIZED components (after ".." collapse) so a path like "x/../.git/config" is
        // still caught. Checking the raw input string would let such traversal slip past.
        Path current = root;
        for (Path part : root.relativize(resolved)) {
            if (BLOCKED_NAMES.stream().anyMatch(name -> name.equalsIgnoreCase(part.toString()))) {
                throw new IllegalArgumentException("Protected path is not available to agent tools: " + part);
            }
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not allowed in project paths: " + relativePath);
            }
        }
        return resolved;
    }
}
