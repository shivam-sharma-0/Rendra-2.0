package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link SandboxPort} without isolation: commands run directly on the backend host in the project
 * folder. Only for local development without Docker (and for tests); {@link
 * com.code.pal.agent.config.SandboxPolicy} refuses to start the agent with it unless
 * {@code app.agent.sandbox.allow-host-execution=true}. Tree operations mirror the Docker adapter:
 * internal and secret folders are never copied, and links are not followed.
 */
@Service
@ConditionalOnProperty(prefix = "app.agent.sandbox", name = "required", havingValue = "false", matchIfMissing = true)
public class LocalProjectEnvironment implements SandboxPort {

    private static final Set<String> NOT_COPIED = Set.of(ProjectPaths.INTERNAL, ".git", ".secrets", "node_modules");
    /** Package-manager shims that Windows only starts through cmd.exe (npm.cmd and friends). */
    private static final Set<String> WINDOWS_SHIMS = Set.of("npm", "npx", "pnpm", "yarn");

    private final AgentProperties properties;
    private final WorkspaceStorage storage;

    public LocalProjectEnvironment(AgentProperties properties, WorkspaceStorage storage) {
        this.properties = properties;
        this.storage = storage;
    }

    @Override
    public void ensure(UUID projectId) {
        storage.ensure(projectId);
    }

    @Override
    public void enforceQuota(UUID projectId) {
        long limit = properties.getSandbox().getMaxProjectBytes();
        if (limit <= 0) return;
        long[] bytes = {0};
        Path internal = project(projectId).resolve(ProjectPaths.INTERNAL);
        walk(project(projectId), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return directory.equals(internal) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) bytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (bytes[0] > limit) {
            throw new IllegalStateException("Project sandbox quota exceeded: " + bytes[0] + " > " + limit + " bytes");
        }
    }

    @Override
    public CommandResult exec(UUID projectId, String relativeRoot, List<String> command) {
        return run(projectId, relativeRoot, command, properties.getSandbox().getCommandTimeoutMs());
    }

    /** Same as {@link #exec}: without a sandbox the host's own network is used. */
    @Override
    public CommandResult install(UUID projectId, String relativeRoot, List<String> command) {
        return run(projectId, relativeRoot, command, properties.getSandbox().getInstallTimeoutMs());
    }

    /** Local development has no browser image: the runtime check is skipped. */
    @Override
    public CommandResult browse(UUID projectId, String siteRoot) {
        return new CommandResult(false, -1, "", CommandResult.NOT_STARTED + ": no browser without the Docker sandbox");
    }

    /** Needs symbolic-link rights (on Windows: Developer Mode); junctions are not used, deletes would follow them. */
    @Override
    public void link(UUID projectId, String linkPath, String targetPath) {
        String link = ProjectPaths.normalize(linkPath, false);
        Path linkFile = root(projectId, link);
        deleteTree(linkFile);
        try {
            Files.createDirectories(linkFile.getParent());
            Files.createSymbolicLink(linkFile, Path.of(ProjectPaths.relativeTarget(link, ProjectPaths.normalize(targetPath, false))));
        } catch (IOException | UnsupportedOperationException error) {
            throw new IllegalStateException("Could not link " + link + " (symbolic links not permitted?): " + error.getMessage(), error);
        }
    }

    private CommandResult run(UUID projectId, String relativeRoot, List<String> command, long timeoutMs) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Sandbox command is empty");
        Path directory = root(projectId, relativeRoot);
        ProcessBuilder builder = new ProcessBuilder(platformCommand(command)).directory(directory.toFile())
                .redirectErrorStream(true);
        sanitizeEnvironment(builder.environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            return new CommandResult(false, -1, "", CommandResult.NOT_STARTED + ": " + error.getMessage());
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int limit = Math.max(1_000, properties.getSandbox().getOutputMaxChars());
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), captured, limit));
        try {
            boolean finished = process.waitFor(Math.max(1_000, timeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            reader.join(1_000);
            String output = captured.toString(StandardCharsets.UTF_8);
            if (!finished) return new CommandResult(false, -1, output, CommandResult.TIMED_OUT);
            return new CommandResult(process.exitValue() == 0, process.exitValue(), output, "");
        } catch (InterruptedException error) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new CommandResult(false, -1, "", "Sandbox command interrupted");
        }
    }

    @Override
    public String commandPath(UUID projectId, String relativeRoot) {
        return root(projectId, relativeRoot).toString();
    }

    @Override
    public void copyTree(UUID projectId, String sourceRoot, String targetRoot) {
        Path source = root(projectId, sourceRoot);
        Path target = root(projectId, targetRoot);
        deleteTree(target);
        try {
            Files.createDirectories(target);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not create " + targetRoot, error);
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) return;
        walk(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (directory.equals(target)) return FileVisitResult.SKIP_SUBTREE;
                if (!directory.equals(source) && (attributes.isSymbolicLink()
                        || NOT_COPIED.contains(directory.getFileName().toString()))) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!ProjectPaths.isInternal(targetRoot)) enforceQuota(projectId);
    }

    @Override
    public void removeTree(UUID projectId, String relativeRoot) {
        if (ProjectPaths.normalize(relativeRoot, true).equals(".")) {
            throw new IllegalArgumentException("Refusing to remove sandbox root");
        }
        deleteTree(root(projectId, relativeRoot));
    }

    @Override
    public void restoreTree(UUID projectId, String snapshotRoot) {
        Path project = project(projectId);
        Path snapshot = root(projectId, snapshotRoot);
        if (!Files.isDirectory(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Snapshot not found: " + snapshotRoot);
        }
        try (var children = Files.list(project)) {
            for (Path child : children.toList()) {
                if (!child.getFileName().toString().equals(ProjectPaths.INTERNAL)) deleteTree(child);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Could not clear the project before restoring", error);
        }
        walk(snapshot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(snapshot) && (attributes.isSymbolicLink()
                        || directory.getFileName().toString().equals(ProjectPaths.INTERNAL))) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(project.resolve(snapshot.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) {
                    Files.copy(file, project.resolve(snapshot.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        enforceQuota(projectId);
    }

    private Path project(UUID projectId) {
        return storage.projectDir(projectId).toAbsolutePath().normalize();
    }

    private Path root(UUID projectId, String relativeRoot) {
        String root = ProjectPaths.normalize(relativeRoot, true);
        Path project = project(projectId);
        Path resolved = root.equals(".") ? project : project.resolve(root).normalize();
        if (!resolved.startsWith(project)) throw new IllegalArgumentException("Path escapes the project: " + relativeRoot);
        return resolved;
    }

    /** Windows starts npm/pnpm/yarn shims only through cmd.exe; every token is still backend-chosen. */
    private static List<String> platformCommand(List<String> command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows || !WINDOWS_SHIMS.contains(command.get(0))) return command;
        List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/d", "/s", "/c"));
        wrapped.addAll(command);
        return wrapped;
    }

    /** Only what package managers need; backend secrets in the environment never reach generated code. */
    private static void sanitizeEnvironment(Map<String, String> environment) {
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        for (String key : List.of("PATH", "Path", "SystemRoot", "ComSpec", "PATHEXT", "TEMP", "TMP",
                "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA")) {
            if (inherited.get(key) != null) environment.put(key, inherited.get(key));
        }
        environment.put("CI", "true");
        environment.put("NO_COLOR", "1");
    }

    private static void drain(InputStream input, ByteArrayOutputStream captured, int limit) {
        try (InputStream stream = input) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) >= 0; ) {
                int remaining = limit - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(count, remaining));
            }
        } catch (IOException ignored) {
            // the process ended; keep what was captured
        }
    }

    private static void walk(Path start, SimpleFileVisitor<Path> visitor) {
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.walkFileTree(start, visitor);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not walk " + start, error);
        }
    }

    @Override
    public void destroy(UUID projectId) {
        Path project = storage.projectDir(projectId);
        deleteTree(project);
        if (Files.exists(project, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Could not remove " + project);
    }

    /** Deletes a tree without following links (a link is removed, not its target). */
    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException error) {
            throw new UncheckedIOException("Could not remove " + root, error);
        }
    }
}
