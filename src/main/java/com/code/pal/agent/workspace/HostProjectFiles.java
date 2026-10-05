package com.code.pal.agent.workspace;

import com.code.pal.agent.change.FileVersions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ProjectFiles} on the backend's own filesystem view of the project directory
 * ({@code <workspace-base>/<projectId>}), which may be a local folder or the WSL share.
 *
 * <p>Generated code can plant symbolic links. Nothing here follows one: reads and existence checks use
 * {@code NOFOLLOW_LINKS}, every existing ancestor of a target must be a plain directory, and listings
 * skip anything that is neither a regular file nor a directory. On the WSL share Windows does not even
 * resolve Linux links (they fail to stat), which the same rules turn into "skip" or "not found".
 */
@Component
@RequiredArgsConstructor
public class HostProjectFiles implements ProjectFiles {

    /** Same limit as sandbox attachments. */
    private static final int MAX_WRITE_BYTES = 10_000_000;
    /** Guards memory; agent tools apply their own, much smaller, limits. */
    private static final long MAX_READ_BYTES = 20_000_000;

    private final WorkspaceStorage storage;

    @Override
    public String readText(UUID projectId, String relativeRoot, String relativePath) {
        return new String(read(requireRegularFile(projectId, relativeRoot, relativePath), MAX_READ_BYTES), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] readBytes(UUID projectId, String relativeRoot, String relativePath, int maxBytes) {
        return read(requireRegularFile(projectId, relativeRoot, relativePath), Math.max(1, maxBytes));
    }

    @Override
    public byte[] readHead(UUID projectId, String relativeRoot, String relativePath, int maxBytes) {
        Path file = requireRegularFile(projectId, relativeRoot, relativePath);
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return input.readNBytes(Math.max(0, maxBytes));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read project file", error);
        }
    }

    @Override
    public void writeText(UUID projectId, String relativeRoot, String relativePath, String content) {
        write(projectId, relativeRoot, relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeBytes(UUID projectId, String relativePath, byte[] content) {
        if (content == null || content.length > MAX_WRITE_BYTES) throw new IllegalArgumentException("Attachment is too large");
        write(projectId, ".", relativePath, content);
    }

    @Override
    public List<Entry> list(UUID projectId, String relativeRoot, int maxDepth, Set<String> skipDirectories) {
        Path dir = root(projectId, relativeRoot);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), Math.max(1, maxDepth), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                    if (path.equals(dir)) return FileVisitResult.CONTINUE;
                    if (attributes.isSymbolicLink() || !attributes.isDirectory() || skipped(path)) return FileVisitResult.SKIP_SUBTREE;
                    entries.add(new Entry(relative(dir, path), true, 0));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    // At the depth limit directories arrive here instead of preVisitDirectory.
                    if (attributes.isRegularFile()) entries.add(new Entry(relative(dir, path), false, attributes.size()));
                    else if (attributes.isDirectory() && !skipped(path)) entries.add(new Entry(relative(dir, path), true, 0));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException error) {
                    return FileVisitResult.CONTINUE;   // e.g. a Linux link seen through the WSL share
                }

                private boolean skipped(Path directory) {
                    return skipDirectories.contains(directory.getFileName().toString());
                }
            });
        } catch (IOException error) {
            throw new IllegalStateException("Could not list project files", error);
        }
        return List.copyOf(entries);
    }

    @Override
    public void delete(UUID projectId, String relativeRoot, String relativePath) {
        Path target = resolve(projectId, relativeRoot, relativePath);
        requirePlainAncestors(projectId, target);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Use the sandbox to remove a directory: " + relativePath);
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException error) {
            throw new IllegalStateException("Could not delete " + relativePath, error);
        }
    }

    @Override
    public void move(UUID projectId, String relativeRoot, String source, String target) {
        Path from = requireRegularFile(projectId, relativeRoot, source);
        Path to = writableTarget(projectId, relativeRoot, target);
        try {
            moveReplacing(from, to);
        } catch (IOException error) {
            throw new IllegalStateException("Could not move " + source + " to " + target, error);
        }
    }

    @Override
    public void copyFile(UUID projectId, String sourceRoot, String source, String targetRoot, String target) {
        Path from = requireRegularFile(projectId, sourceRoot, source);
        Path to = writableTarget(projectId, targetRoot, target);
        Path temp = null;
        try {
            temp = Files.createTempFile(to.getParent(), ".codepal-copy-", ".tmp");
            Files.copy(from, temp, StandardCopyOption.REPLACE_EXISTING);
            moveReplacing(temp, to);
        } catch (IOException error) {
            deleteQuietly(temp);
            throw new IllegalStateException("Could not copy " + source + " to " + target, error);
        }
    }

    @Override
    public boolean exists(UUID projectId, String relativeRoot, String relativePath) {
        Path target = resolve(projectId, relativeRoot, relativePath);
        return plainAncestors(projectId, target) && Files.exists(target, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean regularFile(UUID projectId, String relativeRoot, String relativePath) {
        Path target = resolve(projectId, relativeRoot, relativePath);
        return plainAncestors(projectId, target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public long size(UUID projectId, String relativeRoot, String relativePath) {
        if (!regularFile(projectId, relativeRoot, relativePath)) return 0;
        try {
            return Files.size(resolve(projectId, relativeRoot, relativePath));
        } catch (IOException error) {
            return 0;
        }
    }

    @Override
    public String version(UUID projectId, String relativeRoot, String relativePath) {
        Path target = resolve(projectId, relativeRoot, relativePath);
        if (!plainAncestors(projectId, target)) return "non-file";
        return FileVersions.of(target);
    }

    /* ── path resolution ─────────────────────────────────────────────────────── */

    private Path project(UUID projectId) {
        return storage.projectDir(projectId).toAbsolutePath().normalize();
    }

    private Path root(UUID projectId, String relativeRoot) {
        String root = ProjectPaths.normalize(relativeRoot, true);
        Path project = project(projectId);
        return root.equals(".") ? project : contained(project, project.resolve(root).normalize(), relativeRoot);
    }

    private Path resolve(UUID projectId, String relativeRoot, String relativePath) {
        Path project = project(projectId);
        return contained(project, project.resolve(ProjectPaths.combine(relativeRoot, relativePath)).normalize(), relativePath);
    }

    private static Path contained(Path project, Path target, String requested) {
        // ProjectPaths already rejects "..": this also catches platform quirks such as a drive prefix.
        if (!target.startsWith(project) || target.equals(project)) {
            throw new IllegalArgumentException("Path escapes the project: " + requested);
        }
        return target;
    }

    /** True when the project root and every existing ancestor of {@code target} are plain directories. */
    private boolean plainAncestors(UUID projectId, Path target) {
        Path project = project(projectId);
        if (Files.exists(project, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        for (Path ancestor = target.getParent(); ancestor != null && !ancestor.equals(project); ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    private void requirePlainAncestors(UUID projectId, Path target) {
        if (!plainAncestors(projectId, target)) {
            throw new IllegalStateException("Refusing to follow a link or special entry in the project path: "
                    + project(projectId).relativize(target).toString().replace('\\', '/'));
        }
    }

    private Path requireRegularFile(UUID projectId, String relativeRoot, String relativePath) {
        Path target = resolve(projectId, relativeRoot, relativePath);
        requirePlainAncestors(projectId, target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Not a regular file: " + relativePath);
        }
        return target;
    }

    /** Target whose parents exist as plain directories and which, if present, is a regular file. */
    private Path writableTarget(UUID projectId, String relativeRoot, String relativePath) {
        Path target = resolve(projectId, relativeRoot, relativePath);
        requirePlainAncestors(projectId, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Not a regular file: " + relativePath);
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException error) {
            throw new IllegalStateException("Could not create the folder for " + relativePath, error);
        }
        requirePlainAncestors(projectId, target);   // nothing was swapped in while creating folders
        return target;
    }

    /* ── IO ──────────────────────────────────────────────────────────────────── */

    private void write(UUID projectId, String relativeRoot, String relativePath, byte[] content) {
        Path target = writableTarget(projectId, relativeRoot, relativePath);
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), ".codepal-write-", ".tmp");
            Files.write(temp, content);
            moveReplacing(temp, target);
        } catch (IOException error) {
            deleteQuietly(temp);
            throw new IllegalStateException("Could not write " + relativePath, error);
        }
    }

    private static byte[] read(Path file, long maxBytes) {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) throw new IllegalStateException("File is larger than " + maxBytes + " bytes");
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException("Could not read project file", error);
        }
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static String relative(Path dir, Path path) {
        return dir.relativize(path).toString().replace('\\', '/');
    }
}
