package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.ProjectFiles;

import java.util.List;
import java.util.UUID;

/**
 * Everything the backend needs to know about one kind of project (static site, Node/Vite app, and
 * later Spring Boot or Python): how to recognize it, how to install its dependencies, and which fixed
 * commands validate it. Commands are chosen here, by trusted backend code, from the project's original
 * manifests; model-written command text is never executed.
 *
 * <p>All paths are project-relative roots for {@link ProjectFiles}; commands run in the sandbox with
 * that root as their working directory.
 */
public interface StackPack {

    /** Stable identifier shown to the model and in diagnostics, e.g. {@code "node"}. */
    String id();

    /** How strongly the project under {@code root} looks like this stack; 0 means it does not apply. */
    int detect(ProjectFiles files, UUID projectId, String root);

    /** What the stack's manifests declare: package manager, scripts, available operations. */
    StackCapabilities inspect(ProjectFiles files, UUID projectId, String root);

    /** The fixed command for a validation operation, or an unavailable plan saying why there is none. */
    CommandPlan resolve(ProjectFiles files, UUID projectId, String root, ValidationOperation operation, String scope);

    /**
     * Files whose content decides the installed dependencies (for example {@code package.json} and its
     * lockfile). Empty when the stack has no dependency step.
     */
    default List<String> dependencyManifests() {
        return List.of();
    }

    /** The install command for the project under {@code root}, or empty when nothing needs installing. */
    default List<String> installCommand(ProjectFiles files, UUID projectId, String root) {
        return List.of();
    }

    /**
     * Makes {@code candidateRoot} run only commands the live project already trusted (for Node: the
     * original {@code package.json} scripts), whatever the staged change did to them.
     */
    default void preserveTrustedCommands(ProjectFiles files, UUID projectId, String liveRoot, String candidateRoot) {
    }

    /** Whether the staged change altered the commands validation would run. */
    default boolean trustedCommandsChanged(ProjectFiles files, UUID projectId, String liveRoot, String stagedRoot) {
        return false;
    }
}
