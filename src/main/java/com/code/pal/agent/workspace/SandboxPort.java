package com.code.pal.agent.workspace;

import java.util.List;
import java.util.UUID;

/**
 * Port to a project's isolated environment: its persistent files plus the ability to run trusted
 * backend-chosen commands next to them. Generated code is untrusted, so anything that executes it goes
 * through here and never runs on the backend host.
 *
 * <p>Every path is relative to the project root ({@code relativeRoot} selects a subtree such as a
 * change-set staging area, {@code "."} is the root). Implementations must reject absolute paths,
 * {@code ..} and protected names ({@code .git}, {@code .secrets}, {@code .ssh}). The implementation today is
 * {@link DockerProjectEnvironment}; an nsjail adapter follows in Phase 1.3.
 */
public interface SandboxPort {

    /** Makes the environment ready to use (create or start it); cheap and safe to call repeatedly. */
    void ensure(UUID projectId);

    /** @throws IllegalStateException when the project's files exceed the configured quota */
    void enforceQuota(UUID projectId);

    /** Runs a backend-chosen command in {@code relativeRoot}. Never pass model-written command text. */
    CommandResult exec(UUID projectId, String relativeRoot, List<String> command);

    default CommandResult exec(UUID projectId, List<String> command) {
        return exec(projectId, ".", command);
    }

    String readText(UUID projectId, String relativeRoot, String relativePath);

    default String readText(UUID projectId, String relativePath) {
        return readText(projectId, ".", relativePath);
    }

    /** Binary-safe read for preview/download; fails when the file is larger than {@code maxBytes}. */
    byte[] readBytes(UUID projectId, String relativeRoot, String relativePath, int maxBytes);

    void writeText(UUID projectId, String relativeRoot, String relativePath, String content);

    default void writeText(UUID projectId, String relativePath, String content) {
        writeText(projectId, ".", relativePath, content);
    }

    void writeBytes(UUID projectId, String relativePath, byte[] content);

    List<Entry> list(UUID projectId, String relativeRoot, int maxDepth);

    default List<Entry> list(UUID projectId, int maxDepth) {
        return list(projectId, ".", maxDepth);
    }

    void delete(UUID projectId, String relativeRoot, String relativePath);

    default void delete(UUID projectId, String relativePath) {
        delete(projectId, ".", relativePath);
    }

    void move(UUID projectId, String relativeRoot, String source, String target);

    default void move(UUID projectId, String source, String target) {
        move(projectId, ".", source, target);
    }

    boolean exists(UUID projectId, String relativeRoot, String relativePath);

    boolean regularFile(UUID projectId, String relativeRoot, String relativePath);

    long size(UUID projectId, String relativeRoot, String relativePath);

    /** Content fingerprint for optimistic concurrency: {@code sha256:<hex>}, {@code absent} or {@code non-file}. */
    String version(UUID projectId, String relativeRoot, String relativePath);

    /** Replaces {@code targetRoot} with a copy of {@code sourceRoot} (internal and secret folders excluded). */
    void copyTree(UUID projectId, String sourceRoot, String targetRoot);

    void copyFile(UUID projectId, String sourceRoot, String source, String targetRoot, String target);

    /** Removes a subtree; refuses the project root itself. */
    void removeTree(UUID projectId, String relativeRoot);

    /** Replaces the whole project (except internal state) with the tree at {@code snapshotRoot}. */
    void restoreTree(UUID projectId, String snapshotRoot);

    record Entry(String path, boolean directory, long size) {}

    record CommandResult(boolean success, int exitCode, String stdout, String error) {
        public CommandResult requireSuccess() {
            if (!success) throw new IllegalStateException(error.isBlank() ? stdout : error);
            return this;
        }
    }
}
