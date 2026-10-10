package com.code.pal.agent.workspace;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Port to a project's isolated environment: running trusted backend-chosen commands next to the
 * project's files, plus the bulk tree operations that are cheapest done there in one call. Generated
 * code is untrusted, so anything that executes it goes through here and never runs on the backend host.
 * Single files are read and written through {@link ProjectFiles}, which sees the same folder.
 *
 * <p>Every path is relative to the project root ({@code relativeRoot} selects a subtree such as a
 * change-set staging area, {@code "."} is the root) and follows {@link ProjectPaths}. The adapters are
 * {@link DockerProjectEnvironment} and, for local development without Docker only,
 * {@link LocalProjectEnvironment}; an nsjail adapter follows in Phase 1.3.
 */
public interface SandboxPort {

    /** Makes the environment ready to use (create or start it); cheap and safe to call repeatedly. */
    void ensure(UUID projectId);

    /**
     * Startup check that the environment matches its configuration (for example, that the backend and
     * the sandbox see the same project folders).
     * @throws IllegalStateException with guidance when it does not
     */
    default void verifyEnvironment() {
    }

    /** @throws IllegalStateException when the project's files (internal state excluded) exceed the quota */
    void enforceQuota(UUID projectId);

    /** Runs a backend-chosen command in {@code relativeRoot}. Never pass model-written command text. */
    CommandResult exec(UUID projectId, String relativeRoot, List<String> command);

    default CommandResult exec(UUID projectId, List<String> command) {
        return exec(projectId, ".", command);
    }

    /**
     * Runs a backend-chosen dependency install in {@code relativeRoot} with access to package
     * registries. Only for package-manager installs with lifecycle scripts disabled: this is the one
     * command that gets a network, so it must not execute project or package code.
     */
    CommandResult install(UUID projectId, String relativeRoot, List<String> command);

    /**
     * Makes {@code linkPath} a symbolic link to the directory {@code targetPath}, replacing whatever is
     * there (a previous link or a real directory). The link is relative, so it resolves the same way for
     * the backend and inside the sandbox.
     */
    void link(UUID projectId, String linkPath, String targetPath);

    /**
     * Loads the site in {@code siteRoot} (a folder with an {@code index.html}) in a headless browser with
     * no network and returns the probe's JSON report on stdout: {@code {"issues":[{kind,message,source}],...}}.
     * A result that did not start means no browser is available; callers then skip the check.
     */
    CommandResult browse(UUID projectId, String siteRoot);

    /** {@code relativeRoot} as commands see it, so their output can be mapped back to project paths. */
    String commandPath(UUID projectId, String relativeRoot);

    /** Replaces {@code targetRoot} with a copy of {@code sourceRoot} (internal and secret folders excluded). */
    void copyTree(UUID projectId, String sourceRoot, String targetRoot);

    /** Removes a subtree (absent is fine); refuses the project root itself. */
    void removeTree(UUID projectId, String relativeRoot);

    /** Replaces the whole project (except internal state) with the tree at {@code snapshotRoot}. */
    void restoreTree(UUID projectId, String snapshotRoot);

    /**
     * Removes the project's environment and every file it has, for a project that was deleted. Cannot be
     * undone. Throws if anything is left, so the caller can try again later.
     */
    void destroy(UUID projectId);

    /** Where an app server reaches its database server (host:port), as seen from inside its container. */
    String databaseAddress();

    /** An administrator connection string to the app servers' database server, as the backend reaches it. */
    String databaseAdminUri();

    /**
     * Starts one of the project's app servers (a MERN project's API; {@code name} tells several apart,
     * e.g. "app" and a validation check), replacing one already running under that name. {@code command}
     * runs in {@code relativeRoot} with {@code environment}, the project read-only and no internet; the
     * server listens on {@code port}. Returns how to reach it; it may still be starting.
     */
    ServerEndpoint startServer(UUID projectId, String name, String relativeRoot, List<String> command,
                               java.util.Map<String, String> environment, int port);

    /** How to reach the named server while it runs, or null. */
    ServerEndpoint server(UUID projectId, String name);

    void stopServer(UUID projectId, String name);

    /** The last lines the server wrote (stdout and stderr), for diagnosing a crash. */
    String serverLogs(UUID projectId, String name, int lines);

    /** {@code baseUrl} plus the headers that route a request to the server (through the gateway, if any). */
    record ServerEndpoint(String baseUrl, java.util.Map<String, String> headers) {}

    record CommandResult(boolean success, int exitCode, String stdout, String error) {
        public static final String NOT_STARTED = "Command could not be started";
        public static final String TIMED_OUT = "Command timed out";
        /** The run was stopped; the command was not started or was killed. */
        public static final String CANCELLED = "Command cancelled";

        public CommandResult requireSuccess() {
            if (!success) throw new IllegalStateException(error.isBlank() ? stdout : error);
            return this;
        }

        /** False when the command never ran (the sandbox tooling itself failed to start). */
        public boolean started() {
            return !error.startsWith(NOT_STARTED);
        }

        public boolean cancelled() {
            return CANCELLED.equals(error);
        }

        public boolean timedOut() {
            return error.toLowerCase(Locale.ROOT).startsWith(TIMED_OUT.toLowerCase(Locale.ROOT));
        }
    }
}
