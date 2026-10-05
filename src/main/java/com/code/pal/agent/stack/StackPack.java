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

    /**
     * What decides the installed dependencies under {@code root}, so two roots with the same fingerprint
     * share one install. Defaults to the manifests' exact content.
     */
    default String dependencyFingerprint(ProjectFiles files, UUID projectId, String root) {
        StringBuilder fingerprint = new StringBuilder();
        for (String manifest : dependencyManifests()) {
            fingerprint.append(manifest).append('=').append(files.version(projectId, root, manifest)).append('\n');
        }
        return fingerprint.toString();
    }

    /** Folder the install command fills, relative to the project root (e.g. {@code node_modules}); null if none. */
    default String dependencyDirectory() {
        return null;
    }

    /**
     * Validation commands for a project that has no trusted manifest yet (its first build): fixed
     * commands derived from the staged dependencies, never the staged scripts, so a change cannot
     * define its own passing check.
     */
    default CommandPlan resolveFixed(ProjectFiles files, UUID projectId, String stagedRoot, ValidationOperation operation) {
        return CommandPlan.unavailable(operation, inspect(files, projectId, stagedRoot), "No fixed " + operation.name().toLowerCase() + " command for " + id());
    }

    /**
     * How to produce the site the preview serves, or null when the project's files are served as they
     * are (a static site). The command runs on a copy of the project with dependencies prepared.
     */
    default PreviewBuild previewBuild(ProjectFiles files, UUID projectId, String root) {
        return null;
    }

    /**
     * Makes the project's build-time variables visible to a build of the copy at {@code root}. Never
     * applied to the project itself, so the values do not become project files.
     */
    default void applyBuildVariables(ProjectFiles files, UUID projectId, String root, java.util.Map<String, String> variables) {
    }

    /**
     * The build the runtime check loads: like {@link #previewBuild}, but readable when it fails (for
     * Vite: not minified, so errors name the component that threw).
     */
    default PreviewBuild diagnosticBuild(ProjectFiles files, UUID projectId, String root) {
        return previewBuild(files, projectId, root);
    }

    /**
     * A backend-chosen build command and the folders (relative to the build's root, in order of
     * preference) where it may leave a site with an {@code index.html}.
     */
    record PreviewBuild(List<String> command, List<String> outputDirectories) {}

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
