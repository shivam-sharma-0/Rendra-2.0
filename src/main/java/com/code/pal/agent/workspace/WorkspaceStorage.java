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
 * creating and deleting a project's workspace. Files inside it are accessed through {@link ProjectFiles}
 * (path rules in {@link ProjectPaths}) and commands through {@link SandboxPort}.
 */
@Slf4j
@Service
public class WorkspaceStorage {

    private final Path base;

    public WorkspaceStorage(AgentProperties props) {
        this.base = Path.of(props.getWorkspaceBasePath()).toAbsolutePath().normalize();
    }

    /** Directory holding every project's folder, as this backend sees it. */
    public Path base() {
        return base;
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
