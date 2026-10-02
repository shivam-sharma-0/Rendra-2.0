package com.code.pal.agent.tool;

import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.project.domain.ProjectType;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Context passed to a tool for a single agent run. */
public record AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                           AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace,
                           UUID runId, AgentPlanState plan, DockerProjectEnvironment sandbox,
                           String sandboxRoot) {

    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                        AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace) {
        this(projectId, userId, mode, workspace, projectMetadata, contextTrace,
                UUID.randomUUID(), new AgentPlanState(), null, null);
    }

    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                        AgentProjectMetadata projectMetadata) {
        this(projectId, userId, mode, workspace, projectMetadata, new ProjectContextTrace(),
                UUID.randomUUID(), new AgentPlanState(), null, null);
    }

    /** Compatibility constructor for isolated tool tests and non-runtime callers. */
    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace) {
        this(projectId, userId, mode, workspace, null, new ProjectContextTrace(),
                UUID.randomUUID(), new AgentPlanState(), null, null);
    }

    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                        AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace,
                        UUID runId, AgentPlanState plan, DockerProjectEnvironment sandbox,
                        String sandboxRoot) {
        this.projectId = projectId;
        this.userId = userId;
        this.mode = mode;
        this.workspace = workspace;
        this.projectMetadata = projectMetadata;
        this.contextTrace = contextTrace;
        this.runId = runId;
        this.plan = plan;
        this.sandbox = sandbox;
        this.sandboxRoot = sandboxRoot == null || sandboxRoot.isBlank() ? "." : sandboxRoot;
    }

    public AgentContext(UUID projectId, Long userId, ProjectType mode, Path workspace,
                        AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace,
                        UUID runId, AgentPlanState plan) {
        this(projectId, userId, mode, workspace, projectMetadata, contextTrace, runId, plan, null, null);
    }

    public boolean sandboxed() { return sandbox != null; }

    /** Validate a relative path without touching the host filesystem when running in Docker. */
    public String resolveSandboxPath(String relativePath) {
        if (!sandboxed()) throw new IllegalStateException("Docker sandbox is not attached to this context");
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0
                || isAbsoluteLike(relativePath) || Path.of(relativePath).isAbsolute()) {
            throw new IllegalArgumentException("Project path must be a relative non-empty path");
        }
        for (Path part : Path.of(relativePath)) {
            if (part.toString().equals("..") || part.toString().equalsIgnoreCase(".codepal")) {
                throw new IllegalArgumentException("Protected project path is not available to agent tools");
            }
        }
        return relativePath.replace('\\', '/');
    }

    /** Directory names the agent may never traverse into (repo internals / host credentials). */
    private static final Set<String> BLOCKED_NAMES = Set.of(".git", ".ssh", ".secrets", ".codepal");

    /** Resolve a relative non-symlink path inside this project workspace. */
    public Path resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Project path must not be blank");
        }
        if (isAbsoluteLike(relativePath)) {
            throw new IllegalArgumentException("Absolute project paths are not allowed: " + relativePath);
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

    /** Recognize Windows absolute paths even when the service is running on Linux/WSL. */
    private static boolean isAbsoluteLike(String value) {
        return value.startsWith("\\\\")
                || (value.length() >= 3
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':'
                && (value.charAt(2) == '/' || value.charAt(2) == '\\'));
    }
}
