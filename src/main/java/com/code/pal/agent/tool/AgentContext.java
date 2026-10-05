package com.code.pal.agent.tool;

import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.project.domain.ProjectType;

import java.util.UUID;

/**
 * Context passed to a tool for a single agent run. Tools see the project through {@code files} under
 * {@code root}: the run's staging area for BUILD runs, the project itself ({@code "."}) otherwise.
 */
public record AgentContext(UUID projectId, Long userId, ProjectType mode, String root,
                           AgentProjectMetadata projectMetadata, ProjectContextTrace contextTrace,
                           UUID runId, AgentPlanState plan, ProjectFiles files) {

    public AgentContext {
        root = root == null || root.isBlank() ? "." : ProjectPaths.normalize(root, true);
    }

    /** For tools and tests that need no run identity, plan, or metadata. */
    public AgentContext(UUID projectId, Long userId, ProjectType mode, String root, ProjectFiles files) {
        this(projectId, userId, mode, root, null, new ProjectContextTrace(), UUID.randomUUID(), new AgentPlanState(), files);
    }

    /**
     * A file path a tool may use: project-relative, no traversal, no protected or internal folders.
     * @throws IllegalArgumentException otherwise
     */
    public String resolvePath(String relativePath) {
        return ProjectPaths.agentPath(relativePath, false);
    }

    /** Like {@link #resolvePath} but also accepts {@code "."} for the root itself. */
    public String resolveDirectory(String relativePath) {
        return ProjectPaths.agentPath(relativePath == null || relativePath.isBlank() ? "." : relativePath, true);
    }

    /** {@code relativeDirectory} (from {@link #resolveDirectory}) as a root for {@link ProjectFiles}. */
    public String rootFor(String relativeDirectory) {
        return ".".equals(relativeDirectory) ? root : (".".equals(root) ? relativeDirectory : root + "/" + relativeDirectory);
    }
}
