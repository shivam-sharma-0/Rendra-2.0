package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A project folder under {@code target/test-workspaces} with the production file layer and the local
 * (unsandboxed) environment on top, so tests run the same code paths as the Docker-backed backend.
 */
public final class LocalProject {

    public final AgentProperties properties;
    public final WorkspaceStorage storage;
    public final HostProjectFiles files;
    public final LocalProjectEnvironment sandbox;
    public final UUID projectId = UUID.randomUUID();

    private LocalProject(AgentProperties properties) {
        this.properties = properties;
        try {
            Path base = Path.of("target", "test-workspaces");
            Files.createDirectories(base);
            properties.setWorkspaceBasePath(Files.createTempDirectory(base, "project-").toString());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        this.storage = new WorkspaceStorage(properties);
        this.files = new HostProjectFiles(storage);
        this.sandbox = new LocalProjectEnvironment(properties, storage);
        storage.ensure(projectId);
    }

    public static LocalProject create() {
        return new LocalProject(new AgentProperties());
    }

    public static LocalProject create(AgentProperties properties) {
        return new LocalProject(properties);
    }

    /** The project folder. */
    public Path dir() {
        return storage.projectDir(projectId);
    }

    public Path path(String relative) {
        return dir().resolve(relative);
    }

    public LocalProject write(String relative, String content) {
        try {
            Path target = path(relative);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return this;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public String read(String relative) {
        try {
            return Files.readString(path(relative));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public boolean exists(String relative) {
        return Files.exists(path(relative));
    }
}
