package com.code.pal.agent.workspace;

import com.code.pal.agent.dto.FileContentResponse;
import com.code.pal.agent.dto.FileNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only browser over a project's files, backing the code/files panel in the UI. Traversal is
 * bounded (node/depth caps), never enters heavy or sensitive directories, and every path follows the
 * {@link ProjectPaths} rules, so a crafted {@code path} can never leave the project.
 */
@Service
public class WorkspaceBrowser {

    private static final int MAX_NODES = 800;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILE_BYTES = 200_000;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".ssh", ".secrets", ProjectPaths.INTERNAL, "node_modules", "dist", "build", ".next", ".turbo", ".cache");

    private final ProjectFiles files;

    public WorkspaceBrowser(ProjectFiles files) {
        this.files = files;
    }

    /** The workspace file tree (directories first, then files; both alphabetical). Empty if nothing built yet. */
    public List<FileNode> tree(UUID projectId) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (ProjectFiles.Entry entry : files.list(projectId, ".", MAX_DEPTH, SKIP_DIRS)) {
            String[] parts = entry.path().split("/");
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (current.length() > 0) current.append('/');
                current.append(parts[i]);
                String key = current.toString();
                String nodeName = parts[i];
                Node node = nodes.computeIfAbsent(key, ignored -> new Node(nodeName, key));
                if (i == parts.length - 1) {
                    node.directory = entry.directory();
                    node.size = entry.size();
                } else node.directory = true;
            }
        }
        for (Node node : nodes.values()) {
            int slash = node.path.lastIndexOf('/');
            if (slash >= 0) nodes.get(node.path.substring(0, slash)).children.put(node.name, node);
        }
        int[] budget = {MAX_NODES};
        return nodes.values().stream().filter(node -> !node.path.contains("/"))
                .sorted(Node.ORDER).map(node -> toFileNode(node, budget)).filter(java.util.Objects::nonNull).toList();
    }

    /** Read a workspace file's text content (capped, binary-guarded, path-validated). */
    public FileContentResponse read(UUID projectId, String relativePath) {
        String path = safePath(relativePath);
        if (!files.regularFile(projectId, ".", path)) throw new IllegalArgumentException("File not found: " + relativePath);
        byte[] bytes = files.readHead(projectId, ".", path, MAX_FILE_BYTES + 1);
        if (isBinary(bytes)) return new FileContentResponse(relativePath, "", false, true);
        boolean truncated = bytes.length > MAX_FILE_BYTES;
        int length = truncated ? MAX_FILE_BYTES : bytes.length;
        return new FileContentResponse(relativePath, new String(bytes, 0, length, StandardCharsets.UTF_8), truncated, false);
    }

    /** Depth-first, so at most {@link #MAX_NODES} nodes are returned however the tree is shaped. */
    private static FileNode toFileNode(Node node, int[] budget) {
        if (budget[0] <= 0) return null;
        budget[0]--;
        if (!node.directory) return new FileNode(node.name, node.path, false, node.size, null);
        List<FileNode> children = node.children.values().stream().sorted(Node.ORDER)
                .map(child -> toFileNode(child, budget)).filter(java.util.Objects::nonNull).toList();
        return new FileNode(node.name, node.path, true, 0, children);
    }

    private static String safePath(String relativePath) {
        String path;
        try {
            path = ProjectPaths.normalize(relativePath, false);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid project path");
        }
        for (String part : path.split("/")) {
            if (SKIP_DIRS.contains(part)) throw new IllegalArgumentException("Protected project path");
        }
        return path;
    }

    private static final class Node {
        static final Comparator<Node> ORDER = Comparator.comparing((Node node) -> !node.directory)
                .thenComparing(node -> node.name.toLowerCase());
        final String name; final String path; final Map<String, Node> children = new LinkedHashMap<>();
        boolean directory; long size;
        Node(String name, String path) { this.name = name; this.path = path; }
    }

    private static boolean isBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }
}
