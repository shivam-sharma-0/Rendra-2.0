package com.code.pal.agent.workspace;

import java.util.List;
import java.util.UUID;

/**
 * Single-file access to a project's files from the backend. Bulk tree operations (copy, remove,
 * restore, quota) stay on {@link SandboxPort}, where they run natively next to the files in one call.
 *
 * <p>Paths follow {@link ProjectPaths}. Generated code can plant symbolic links and other special
 * entries in the project, so implementations never follow them: such entries are skipped when listing
 * and rejected when read or written through. Writes do not check the project quota; callers enforce it
 * at commit and upload boundaries.
 */
public interface ProjectFiles {

    String readText(UUID projectId, String relativeRoot, String relativePath);

    default String readText(UUID projectId, String relativePath) {
        return readText(projectId, ".", relativePath);
    }

    /** @throws IllegalStateException when the file is larger than {@code maxBytes} */
    byte[] readBytes(UUID projectId, String relativeRoot, String relativePath, int maxBytes);

    /** Atomic replace: readers see the old or the new content, never a partial file. */
    void writeText(UUID projectId, String relativeRoot, String relativePath, String content);

    default void writeText(UUID projectId, String relativePath, String content) {
        writeText(projectId, ".", relativePath, content);
    }

    void writeBytes(UUID projectId, String relativePath, byte[] content);

    /** Regular files and directories under {@code relativeRoot}, depth-limited; special entries are skipped. */
    List<SandboxPort.Entry> list(UUID projectId, String relativeRoot, int maxDepth);

    default List<SandboxPort.Entry> list(UUID projectId, int maxDepth) {
        return list(projectId, ".", maxDepth);
    }

    /** Deletes one file (absent is fine). Directories are removed with {@link SandboxPort#removeTree}. */
    void delete(UUID projectId, String relativeRoot, String relativePath);

    default void delete(UUID projectId, String relativePath) {
        delete(projectId, ".", relativePath);
    }

    /** Moves one file, replacing the target, creating its parent directories. */
    void move(UUID projectId, String relativeRoot, String source, String target);

    void copyFile(UUID projectId, String sourceRoot, String source, String targetRoot, String target);

    boolean exists(UUID projectId, String relativeRoot, String relativePath);

    boolean regularFile(UUID projectId, String relativeRoot, String relativePath);

    long size(UUID projectId, String relativeRoot, String relativePath);

    /** {@code sha256:<hex>}, {@code absent} or {@code non-file}, identical to the sandbox fingerprint. */
    String version(UUID projectId, String relativeRoot, String relativePath);
}
