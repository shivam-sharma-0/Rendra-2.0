package com.code.pal.agent.workspace;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single-file access to a project's files from the backend. Bulk tree operations (copy, remove,
 * restore, quota) and commands stay on {@link SandboxPort}, where they run natively next to the files.
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

    /** The first {@code maxBytes} bytes of a file (all of it when smaller), for previews and truncated reads. */
    byte[] readHead(UUID projectId, String relativeRoot, String relativePath, int maxBytes);

    /** Atomic replace: readers see the old or the new content, never a partial file. */
    void writeText(UUID projectId, String relativeRoot, String relativePath, String content);

    default void writeText(UUID projectId, String relativePath, String content) {
        writeText(projectId, ".", relativePath, content);
    }

    void writeBytes(UUID projectId, String relativePath, byte[] content);

    /**
     * Regular files and directories under {@code relativeRoot}, depth-limited; special entries are
     * skipped. Directories named in {@code skipDirectories} (exact name, any depth) are neither listed nor
     * entered, which keeps heavy trees such as {@code node_modules} from being walked at all.
     */
    List<Entry> list(UUID projectId, String relativeRoot, int maxDepth, Set<String> skipDirectories);

    default List<Entry> list(UUID projectId, String relativeRoot, int maxDepth) {
        return list(projectId, relativeRoot, maxDepth, Set.of());
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

    /** Content fingerprint for optimistic concurrency: {@code sha256:<hex>}, {@code absent} or {@code non-file}. */
    String version(UUID projectId, String relativeRoot, String relativePath);

    /** A listed file or directory; {@code path} is relative to the listed root, with forward slashes. */
    record Entry(String path, boolean directory, long size) {}
}
