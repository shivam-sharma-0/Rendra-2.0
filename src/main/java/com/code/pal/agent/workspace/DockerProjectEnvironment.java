package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent per-project Docker environment. The project volume is the source of truth; this
 * adapter deliberately has no host-directory fallback. File paths are relative to /workspace.
 *
 * <p>Each project has a read/write lock. Commands inside the container hold the read lock, so they
 * run in parallel; provisioning (volume + container + start) and idle stop/remove hold the write
 * lock, so a container is never created twice by concurrent callers and never stopped under a
 * running command. Once verified, a project is "ready" and later calls skip the Docker checks; a
 * command that finds the container stopped or removed clears that state, re-provisions, and is
 * retried once.
 */
@Slf4j
@Service
public class DockerProjectEnvironment {
    private static final int MAX_PATH_LENGTH = 512;
    private static final String WORKSPACE_LABEL = "com.codepal.workspace=true";
    /** Bounds re-checks when the container is stopped between ensure() and taking the read lock. */
    private static final int MAX_EXEC_ROUNDS = 3;

    private final AgentProperties properties;
    private final CommandRunner runner;
    private final Clock clock;
    private final Map<UUID, Instant> lastUsed = new ConcurrentHashMap<>();
    /** Projects whose volume and running container this process has verified. */
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ReentrantReadWriteLock> projectLocks = new ConcurrentHashMap<>();
    private final Instant startedAt;

    @Autowired
    public DockerProjectEnvironment(AgentProperties properties) {
        this(properties, null, Clock.systemUTC());
    }

    /** Test seam: {@code runner} receives Docker CLI arguments without the docker executable. */
    DockerProjectEnvironment(AgentProperties properties, CommandRunner runner, Clock clock) {
        this.properties = properties;
        this.runner = runner != null ? runner : this::runProcess;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public Environment ensure(UUID projectId) {
        lastUsed.put(projectId, clock.instant());
        if (!ready.contains(projectId)) {
            Lock lock = lockFor(projectId).writeLock();
            lock.lock();
            try {
                if (!ready.contains(projectId)) {
                    provision(projectId);
                    ready.add(projectId);
                }
            } finally {
                lock.unlock();
            }
        }
        return environment(projectId);
    }

    private void provision(UUID projectId) {
        Environment environment = environment(projectId);
        String volume = environment.volume();
        String container = environment.container();
        if (!succeeds(List.of("volume", "inspect", volume), Duration.ofSeconds(20), null)) {
            run(List.of("volume", "create", "--label", WORKSPACE_LABEL, "--label",
                    "com.codepal.project=" + projectId, volume), Duration.ofSeconds(30), null).requireSuccess();
        }
        CommandResult inspected = run(List.of("container", "inspect", container), Duration.ofSeconds(20), null);
        if (!inspected.success()) {
            CommandResult created = run(List.of("run", "-d", "--name", container, "--label", WORKSPACE_LABEL,
                    "--label", "com.codepal.project=" + projectId,
                    "--cpus", Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                    "--memory", Math.max(256, properties.getSandbox().getMemoryMb()) + "m",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--mount", "type=volume,source=" + volume + ",target=" + properties.getSandbox().getWorkspacePath(),
                    "--workdir", properties.getSandbox().getWorkspacePath(),
                    properties.getSandbox().getImage(), "sh", "-lc",
                    "while :; do sleep 3600; done"), Duration.ofSeconds(90), null);
            // Another backend process may have created it between inspect and run; that is fine.
            if (!created.success()) {
                if (!created.stdout().contains("is already in use")) created.requireSuccess();
                log.info("sandbox_container_created_elsewhere projectId={}", projectId);
                startIfStopped(container);
            } else {
                log.info("sandbox_container_created projectId={}", projectId);
            }
        } else {
            startIfStopped(container);
        }
        // Internal manifests and snapshots live under .codepal, which is also used as a
        // working directory by artifact operations. Create it before any container exec.
        run(List.of("exec", "--workdir", environment.workspacePath(), container,
                        "mkdir", "-p", environment.workspacePath() + "/.codepal"),
                Duration.ofSeconds(30), null).requireSuccess();
    }

    private void startIfStopped(String container) {
        CommandResult running = run(List.of("container", "inspect", "-f", "{{.State.Running}}", container),
                Duration.ofSeconds(20), null);
        if (!running.stdout().trim().equalsIgnoreCase("true")) {
            run(List.of("start", container), Duration.ofSeconds(30), null).requireSuccess();
            log.info("sandbox_container_started container={}", container);
        }
    }

    private Environment environment(UUID projectId) {
        return new Environment(projectId,
                resourceName(properties.getSandbox().getContainerPrefix(), projectId),
                resourceName(properties.getSandbox().getVolumePrefix(), projectId),
                properties.getSandbox().getWorkspacePath());
    }

    private ReentrantReadWriteLock lockFor(UUID projectId) {
        return projectLocks.computeIfAbsent(projectId, ignored -> new ReentrantReadWriteLock());
    }

    /**
     * Runs {@code docker exec [options] <container> command} under the project's read lock, so idle
     * cleanup cannot stop the container while the command runs. If Docker still reports the
     * container as stopped or missing (daemon restart, manual removal, another backend process), the
     * ready state is dropped, the container is provisioned again, and the command is retried once.
     * The command never started in that case, so the retry cannot apply it twice.
     */
    private CommandResult execInContainer(UUID projectId, List<String> options, List<String> command,
                                          Duration timeout, byte[] stdin, int outputLimit) {
        boolean retried = false;
        for (int round = 1; ; round++) {
            Environment environment = ensure(projectId);
            List<String> args = new ArrayList<>();
            args.add("exec");
            args.addAll(options);
            args.add(environment.container());
            args.addAll(command);
            Lock lock = lockFor(projectId).readLock();
            CommandResult result;
            lock.lock();
            try {
                // Idle cleanup may have stopped the container between ensure() and this lock.
                if (!ready.contains(projectId) && round < MAX_EXEC_ROUNDS) continue;
                result = run(args, timeout, stdin, outputLimit);
            } finally {
                lock.unlock();
            }
            if (retried || !containerUnavailable(result)) return result;
            log.warn("sandbox_container_unavailable projectId={} action=reprovision_and_retry", projectId);
            ready.remove(projectId);
            retried = true;
        }
    }

    /**
     * Docker prints this before the command starts, so the error must be the start of the output; a
     * command whose own output merely mentions it (e.g. a failing build) is not retried.
     */
    private static boolean containerUnavailable(CommandResult result) {
        if (result.success()) return false;
        String output = result.stdout().stripLeading().toLowerCase(Locale.ROOT);
        if (!output.startsWith("error response from daemon")) return false;
        String firstLine = output.lines().findFirst().orElse("");
        return firstLine.contains("is not running") || firstLine.contains("no such container");
    }

    /** Keep running containers bounded while retaining project data in named volumes. */
    @Scheduled(fixedDelayString = "${app.agent.sandbox.cleanup-interval-ms:60000}")
    public void cleanupIdleSandboxes() {
        if (!properties.getSandbox().isRequired()) return;
        discoverManagedContainers();
        Instant now = clock.instant();
        long stopAfter = Math.max(60_000, properties.getSandbox().getIdleStopMs());
        long removeAfter = Math.max(stopAfter, properties.getSandbox().getIdleRemoveMs());
        for (Map.Entry<UUID, Instant> entry : lastUsed.entrySet()) {
            if (!entry.getValue().equals(lastUsed.get(entry.getKey()))) continue;
            long idleMs = Duration.between(entry.getValue(), now).toMillis();
            if (idleMs < stopAfter) continue;
            // The write lock waits for no one: a busy project (provisioning or running a command) is
            // in use, so skip it this round instead of blocking the shared scheduler thread.
            Lock lock = lockFor(entry.getKey()).writeLock();
            if (!lock.tryLock()) continue;
            try {
                cleanupIdle(entry.getKey(), entry.getValue(), idleMs, removeAfter);
            } finally {
                lock.unlock();
            }
        }
    }

    private void cleanupIdle(UUID projectId, Instant seenLastUsed, long idleMs, long removeAfter) {
        String container = resourceName(properties.getSandbox().getContainerPrefix(), projectId);
        CommandResult status = run(List.of("container", "inspect", "-f", "{{.State.Status}}", container),
                Duration.ofSeconds(20), null);
        if (!status.success()) {
            ready.remove(projectId);
            lastUsed.remove(projectId, seenLastUsed);
            return;
        }
        if ("running".equalsIgnoreCase(status.stdout().trim())) {
            if (!seenLastUsed.equals(lastUsed.get(projectId))) return;
            ready.remove(projectId);
            run(List.of("stop", "-t", "10", container), Duration.ofSeconds(30), null);
            log.info("sandbox_container_stopped projectId={} idleMs={}", projectId, idleMs);
            return;
        }
        if (idleMs >= removeAfter) {
            if (!seenLastUsed.equals(lastUsed.get(projectId))) return;
            ready.remove(projectId);
            run(List.of("rm", container), Duration.ofSeconds(30), null);
            lastUsed.remove(projectId, seenLastUsed);
            log.info("sandbox_container_removed projectId={} idleMs={}", projectId, idleMs);
        }
    }

    private void discoverManagedContainers() {
        CommandResult containers = run(List.of("container", "ls", "-aq", "--filter", "label=" + WORKSPACE_LABEL),
                Duration.ofSeconds(20), null);
        if (!containers.success()) return;
        for (String id : containers.stdout().split("\\R")) {
            if (id.isBlank()) continue;
            CommandResult name = run(List.of("container", "inspect", "-f", "{{.Name}}", id.trim()),
                    Duration.ofSeconds(20), null);
            if (!name.success()) continue;
            String value = name.stdout().trim().replaceFirst("^/", "");
            String prefix = properties.getSandbox().getContainerPrefix();
            if (!value.startsWith(prefix)) continue;
            try { lastUsed.putIfAbsent(UUID.fromString(value.substring(prefix.length())), startedAt); }
            catch (IllegalArgumentException ignored) { /* ignore unrelated labelled containers */ }
        }
    }

    /** Enforce a project volume quota without counting internal snapshots/change sets. */
    public void enforceQuota(UUID projectId) {
        long limit = properties.getSandbox().getMaxProjectBytes();
        if (limit <= 0) return;
        CommandResult result = execInContainer(projectId, List.of(), List.of(
                        "sh", "-lc", "du -sk --exclude=.codepal " + shellQuote(properties.getSandbox().getWorkspacePath())),
                timeout(), null, outputLimit());
        result.requireSuccess();
        String first = result.stdout().trim().split("\\s+", 2)[0];
        long bytes = parseSize(first) * 1024L;
        if (bytes > limit) {
            throw new IllegalStateException("Project sandbox quota exceeded: " + bytes + " > " + limit + " bytes");
        }
    }

    public CommandResult exec(UUID projectId, List<String> command) {
        return exec(projectId, ".", command);
    }

    /** Execute a trusted backend command in a project-relative directory. */
    public CommandResult exec(UUID projectId, String relativeRoot, List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Sandbox command is empty");
        return execInContainer(projectId, List.of("--workdir", rootPath(relativeRoot)), command,
                timeout(), null, outputLimit());
    }

    public String readText(UUID projectId, String relativePath) {
        return readText(projectId, ".", relativePath);
    }

    public String readText(UUID projectId, String relativeRoot, String relativePath) {
        String path = safePath(relativeRoot, relativePath);
        return exec(projectId, relativeRoot, List.of("cat", "--", path)).requireSuccess().stdout();
    }

    /** Read binary-safe content from the volume for preview/download endpoints. */
    public byte[] readBytes(UUID projectId, String relativeRoot, String relativePath, int maxBytes) {
        String path = safePath(relativeRoot, relativePath);
        int limit = Math.max(1, maxBytes);
        String script = "size=$(wc -c < " + shellQuote(path) + ") && test $size -le " + limit
                + " && base64 -w 0 -- " + shellQuote(path);
        CommandResult result = execInContainer(projectId, List.of("--workdir", rootPath(relativeRoot)),
                List.of("sh", "-lc", script), timeout(), null, Math.max(1_000, limit * 2 + 100));
        result.requireSuccess();
        try { return Base64.getDecoder().decode(result.stdout().trim()); }
        catch (IllegalArgumentException error) { throw new IllegalStateException("Could not decode sandbox file", error); }
    }

    public void writeText(UUID projectId, String relativePath, String content) {
        writeText(projectId, ".", relativePath, content);
    }

    public void writeText(UUID projectId, String relativeRoot, String relativePath, String content) {
        String path = safePath(relativeRoot, relativePath);
        String parent = path.substring(0, Math.max(path.lastIndexOf('/'), properties.getSandbox().getWorkspacePath().length()));
        String script = "mkdir -p " + shellQuote(parent) + " && cat > " + shellQuote(path);
        execInContainer(projectId, List.of("-i", "--workdir", rootPath(relativeRoot)), List.of("sh", "-lc", script),
                timeout(), content.getBytes(StandardCharsets.UTF_8), outputLimit()).requireSuccess();
        enforceQuota(projectId);
    }

    public void writeBytes(UUID projectId, String relativePath, byte[] content) {
        if (content == null || content.length > 10_000_000) throw new IllegalArgumentException("Attachment is too large");
        String path = safePath(".", relativePath);
        String parent = parentPath(path);
        String script = "mkdir -p " + shellQuote(parent) + " && base64 -d > " + shellQuote(path);
        String encoded = Base64.getEncoder().encodeToString(content);
        execInContainer(projectId, List.of("-i", "--workdir", rootPath(".")), List.of("sh", "-lc", script),
                timeout(), encoded.getBytes(StandardCharsets.US_ASCII), outputLimit()).requireSuccess();
        enforceQuota(projectId);
    }

    public List<Entry> list(UUID projectId, int maxDepth) {
        return list(projectId, ".", maxDepth);
    }

    public List<Entry> list(UUID projectId, String relativeRoot, int maxDepth) {
        String root = rootPath(relativeRoot);
        CommandResult result = execInContainer(projectId, List.of(), List.of(
                        "find", root, "-mindepth", "1", "-maxdepth", Integer.toString(Math.max(1, maxDepth)),
                        "-printf", "%y\\t%p\\t%s\\n"), timeout(), null, outputLimit()).requireSuccess();
        List<Entry> entries = new ArrayList<>();
        for (String line : result.stdout().split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", 3);
            if (parts.length != 3) continue;
            String path = parts[1].substring(root.length()).replace('\\', '/');
            if (path.startsWith("/")) path = path.substring(1);
            entries.add(new Entry(path, "d".equals(parts[0]), parseSize(parts[2])));
        }
        return List.copyOf(entries);
    }

    public void delete(UUID projectId, String relativePath) {
        delete(projectId, ".", relativePath);
    }

    public void delete(UUID projectId, String relativeRoot, String relativePath) {
        exec(projectId, relativeRoot, List.of("rm", "-rf", "--", safePath(relativeRoot, relativePath))).requireSuccess();
    }

    public void move(UUID projectId, String source, String target) {
        move(projectId, ".", source, target);
    }

    public void move(UUID projectId, String relativeRoot, String source, String target) {
        exec(projectId, relativeRoot, List.of("mkdir", "-p", "--", parentPath(safePath(relativeRoot, target)))).requireSuccess();
        exec(projectId, relativeRoot, List.of("mv", "--", safePath(relativeRoot, source), safePath(relativeRoot, target))).requireSuccess();
    }

    public boolean exists(UUID projectId, String relativeRoot, String relativePath) {
        return exec(projectId, relativeRoot, List.of("test", "-e", safePath(relativeRoot, relativePath))).success();
    }

    public boolean regularFile(UUID projectId, String relativeRoot, String relativePath) {
        return exec(projectId, relativeRoot, List.of("test", "-f", safePath(relativeRoot, relativePath))).success();
    }

    public long size(UUID projectId, String relativeRoot, String relativePath) {
        CommandResult result = exec(projectId, relativeRoot,
                List.of("stat", "-c", "%s", safePath(relativeRoot, relativePath)));
        if (!result.success()) return 0;
        return parseSize(result.stdout().trim());
    }

    /** Stable content fingerprint used for optimistic concurrency in the agent tools. */
    public String version(UUID projectId, String relativeRoot, String relativePath) {
        if (!exists(projectId, relativeRoot, relativePath)) return "absent";
        if (!regularFile(projectId, relativeRoot, relativePath)) return "non-file";
        return "sha256:" + exec(projectId, relativeRoot,
                List.of("sha256sum", safePath(relativeRoot, relativePath))).requireSuccess().stdout().trim().split("\\s+", 2)[0];
    }

    /** Copy a directory inside the persistent project volume without exposing it to the host. */
    public void copyTree(UUID projectId, String sourceRoot, String targetRoot) {
        String source = rootPath(sourceRoot);
        String target = rootPath(targetRoot);
        String parent = parentPath(target);
        String script = "rm -rf " + shellQuote(target) + " && mkdir -p " + shellQuote(target)
                + " && tar -C " + shellQuote(source) + " --exclude=.codepal --exclude=.git --exclude=.secrets"
                + " -cf - . | tar -C " + shellQuote(target) + " -xf -";
        exec(projectId, ".", List.of("sh", "-lc", "mkdir -p " + shellQuote(parent) + " && " + script)).requireSuccess();
        enforceQuota(projectId);
    }

    public void copyFile(UUID projectId, String sourceRoot, String source, String targetRoot, String target) {
        String sourcePath = safePath(sourceRoot, source);
        String targetPath = safePath(targetRoot, target);
        String script = "mkdir -p " + shellQuote(parentPath(targetPath)) + " && cp -f "
                + shellQuote(sourcePath) + " " + shellQuote(targetPath);
        exec(projectId, ".", List.of("sh", "-lc", script)).requireSuccess();
        enforceQuota(projectId);
    }

    public void removeTree(UUID projectId, String relativeRoot) {
        String root = rootPath(relativeRoot);
        if ("/".equals(root) || root.equals(properties.getSandbox().getWorkspacePath())) {
            throw new IllegalArgumentException("Refusing to remove sandbox root");
        }
        exec(projectId, ".", List.of("rm", "-rf", "--", root)).requireSuccess();
    }

    /** Restore a complete project tree from an internal snapshot while preserving the snapshot store. */
    public void restoreTree(UUID projectId, String snapshotRoot) {
        String source = rootPath(snapshotRoot);
        String workspace = properties.getSandbox().getWorkspacePath();
        String script = "find " + shellQuote(workspace) + " -mindepth 1 -maxdepth 1 ! -name .codepal -exec rm -rf -- {} +"
                + " && tar -C " + shellQuote(source) + " --exclude=.codepal -cf - . | tar -C "
                + shellQuote(workspace) + " -xf -";
        exec(projectId, ".", List.of("sh", "-lc", script)).requireSuccess();
        enforceQuota(projectId);
    }

    private String safePath(String relativeRoot, String relativePath) {
        String root = normalizeRelative(relativeRoot, true);
        String path = normalizeRelative(relativePath, false);
        String combined = root.equals(".") ? path : root + "/" + path;
        return properties.getSandbox().getWorkspacePath() + "/" + combined.replaceAll("/{2,}", "/");
    }

    private String rootPath(String relativeRoot) {
        String root = normalizeRelative(relativeRoot, true);
        return root.equals(".") ? properties.getSandbox().getWorkspacePath()
                : properties.getSandbox().getWorkspacePath() + "/" + root;
    }

    private String normalizeRelative(String relativePath, boolean allowDot) {
        if (relativePath == null || relativePath.isBlank() || relativePath.length() > MAX_PATH_LENGTH
                || relativePath.indexOf('\0') >= 0 || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid sandbox path");
        }
        String normalized = relativePath.replace('\\', '/');
        for (String part : normalized.split("/")) {
            if (part.equals("..") || part.equalsIgnoreCase(".git") || part.equalsIgnoreCase(".secrets")
                    || part.equalsIgnoreCase(".ssh")) {
                throw new IllegalArgumentException("Protected sandbox path");
            }
        }
        if (!allowDot && (normalized.equals(".") || normalized.isBlank())) {
            throw new IllegalArgumentException("File path must not be a directory root");
        }
        return normalized.replaceAll("/{2,}", "/");
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= properties.getSandbox().getWorkspacePath().length() ? properties.getSandbox().getWorkspacePath() : path.substring(0, slash);
    }

    private Duration timeout() { return Duration.ofMillis(Math.max(1_000, properties.getSandbox().getCommandTimeoutMs())); }

    private boolean succeeds(List<String> args, Duration timeout, byte[] stdin) {
        return run(args, timeout, stdin).success();
    }

    private int outputLimit() { return properties.getSandbox().getOutputMaxChars(); }

    private CommandResult run(List<String> args, Duration timeout, byte[] stdin) {
        return run(args, timeout, stdin, outputLimit());
    }

    private CommandResult run(List<String> args, Duration timeout, byte[] stdin, int outputLimit) {
        return runner.run(args, timeout, stdin, outputLimit);
    }

    private CommandResult runProcess(List<String> args, Duration timeout, byte[] stdin, int outputLimit) {
        List<String> command = new ArrayList<>();
        command.add(properties.getSandbox().getDockerCommand());
        String configuredArgs = properties.getSandbox().getDockerCommandArgs();
        if (configuredArgs != null && !configuredArgs.isBlank()) {
            command.addAll(List.of(configuredArgs.trim().split("\\s+")));
        }
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (stdin != null) {
                try (OutputStream output = process.getOutputStream()) { output.write(stdin); }
            }
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try { process.getInputStream().transferTo(new BoundedOutputStream(captured, outputLimit)); }
                catch (IOException ignored) { }
            });
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1_000);
                return new CommandResult(false, -1, captured.toString(StandardCharsets.UTF_8), "Sandbox command timed out");
            }
            reader.join(1_000);
            return new CommandResult(process.exitValue() == 0, process.exitValue(), captured.toString(StandardCharsets.UTF_8), "");
        } catch (IOException e) {
            return new CommandResult(false, -1, "", "Docker command could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, -1, "", "Sandbox command interrupted");
        }
    }

    private static String resourceName(String prefix, UUID projectId) {
        return (prefix == null ? "codepal-" : prefix).replaceAll("[^A-Za-z0-9_.-]", "") + projectId;
    }

    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static long parseSize(String value) { try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; } }

    /** Executes one Docker CLI invocation; {@code args} exclude the docker executable itself. */
    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> args, Duration timeout, byte[] stdin, int outputLimit);
    }

    public record Environment(UUID projectId, String container, String volume, String workspacePath) {}
    public record Entry(String path, boolean directory, long size) {}
    public record CommandResult(boolean success, int exitCode, String stdout, String error) {
        public CommandResult requireSuccess() {
            if (!success) throw new IllegalStateException(error.isBlank() ? stdout : error);
            return this;
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private BoundedOutputStream(ByteArrayOutputStream delegate, int limit) { this.delegate = delegate; this.limit = Math.max(1, limit); }
        @Override public void write(int b) { if (delegate.size() < limit) delegate.write(b); }
        @Override public void write(byte[] bytes, int offset, int length) { if (delegate.size() < limit) delegate.write(bytes, offset, Math.min(length, limit - delegate.size())); }
    }
}
