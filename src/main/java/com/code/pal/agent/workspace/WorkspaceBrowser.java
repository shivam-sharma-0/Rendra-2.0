package com.code.pal.agent.workspace;

import com.code.pal.agent.dto.FileContentResponse;
import com.code.pal.agent.dto.FileNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only browser over a project's agent workspace, backing the code/files panel in the UI.
 * Traversal is bounded (node/depth caps), skips heavy or sensitive directories, and every path is
 * validated against traversal + symlink escape so a crafted {@code path} can never leave the project.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceBrowser {

    private static final int MAX_NODES = 800;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILE_BYTES = 200_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".ssh", ".secrets", "node_modules", "dist", "build", ".next", ".turbo", ".cache");

    private final WorkspaceStorage storage;

    /** The workspace file tree (directories first, then files; both alphabetical). Empty if nothing built yet. */
    public List<FileNode> tree(UUID projectId) {
        Path root = storage.projectDir(projectId).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        return listDir(root, root, 0, new int[]{0});
    }

    private List<FileNode> listDir(Path root, Path dir, int depth, int[] count) {
        if (depth >= MAX_DEPTH || count[0] >= MAX_NODES) return List.of();
        List<Path> entries;
        try (var stream = Files.list(dir)) {
            entries = stream.sorted(dirsFirst()).toList();
        } catch (IOException e) {
            return List.of();
        }
        List<FileNode> nodes = new ArrayList<>();
        for (Path entry : entries) {
            if (count[0] >= MAX_NODES) break;
            String name = entry.getFileName().toString();
            boolean isDir = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
            if (isDir && SKIP_DIRS.contains(name)) continue;
            if (Files.isSymbolicLink(entry)) continue; // never surface links out of the tree
            count[0]++;
            String rel = root.relativize(entry).toString().replace('\\', '/');
            if (isDir) {
                nodes.add(new FileNode(name, rel, true, 0, listDir(root, entry, depth + 1, count)));
            } else {
                nodes.add(new FileNode(name, rel, false, sizeOf(entry), null));
            }
        }
        return nodes;
    }

    /** Read a workspace file's text content (capped, binary-guarded, path-validated). */
    public FileContentResponse read(UUID projectId, String relativePath) {
        Path root = storage.projectDir(projectId).toAbsolutePath().normalize();
        Path file = resolveSafe(root, relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + relativePath);
        }
        if (isBinary(bytes)) return new FileContentResponse(relativePath, "", false, true);
        boolean truncated = bytes.length > MAX_FILE_BYTES;
        int length = truncated ? MAX_FILE_BYTES : bytes.length;
        return new FileContentResponse(relativePath, new String(bytes, 0, length, StandardCharsets.UTF_8), truncated, false);
    }

    /** Resolve a relative path inside the workspace, rejecting traversal, absolute paths and symlinks. */
    private static Path resolveSafe(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Project path must not be blank");
        }
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute() || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Path escapes the project workspace");
        }
        for (Path part : requested) {
            if (part.toString().equals("..")) throw new IllegalArgumentException("Parent path traversal is not allowed");
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes the project workspace");
        }
        Path current = root;
        for (Path part : root.relativize(resolved)) {
            if (SKIP_DIRS.contains(part.toString())) throw new IllegalArgumentException("Protected path");
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not allowed");
            }
        }
        return resolved;
    }

    private static Comparator<Path> dirsFirst() {
        return Comparator
                .comparing((Path p) -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString().toLowerCase());
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean isBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }
}
