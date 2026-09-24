package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

/**
 * Owns each project's on-disk code directory ({@code <base>/<projectId>}). Central place for
 * creating, deleting, and — crucially — <b>safely resolving</b> a relative path so a tool can never
 * escape its project's folder (path-traversal guard).
 */
@Slf4j
@Service
public class WorkspaceStorage {

    private final Path base;

    public WorkspaceStorage(AgentProperties props) {
        this.base = Path.of(props.getWorkspaceBasePath()).toAbsolutePath().normalize();
    }

    /** This project's root directory (not guaranteed to exist yet). */
    public Path projectDir(UUID projectId) {
        return base.resolve(projectId.toString()).normalize();
    }

    /** Create the project directory if missing; returns it. */
    public Path ensure(UUID projectId) {
        Path dir = projectDir(projectId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create workspace " + projectId, e);
        }
        return dir;
    }

    /**
     * Resolve a caller-supplied relative path inside the project, rejecting anything that escapes
     * the project directory (e.g. {@code ../}, absolute paths, symlink tricks after normalize).
     */
    public Path resolveSafe(UUID projectId, String relativePath) {
        Path dir = projectDir(projectId);
        Path resolved = dir.resolve(relativePath).normalize();
        if (!resolved.startsWith(dir)) {
            throw new IllegalArgumentException("Path escapes the project workspace: " + relativePath);
        }
        return resolved;
    }

    /** Recursively delete the project's directory (workspace cleanup on project delete). */
    public void delete(UUID projectId) {
        Path dir = projectDir(projectId);
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Workspace delete: could not remove {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Workspace delete failed for {}: {}", projectId, e.getMessage());
        }
    }
}
