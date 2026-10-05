package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-project Docker container with the project folder ({@code <workspace-base>/<projectId>}, in WSL
 * ext4) bind-mounted at /workspace. It runs commands and bulk tree operations; the backend reads and
 * writes single files directly through {@link HostProjectFiles}, which sees the same folder.
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
@ConditionalOnProperty(prefix = "app.agent.sandbox", name = "required", havingValue = "true")
public class DockerProjectEnvironment implements SandboxPort {
    private static final String WORKSPACE_LABEL = "com.codepal.workspace=true";
    /** Bounds re-checks when the container is stopped between ensure() and taking the read lock. */
    private static final int MAX_EXEC_ROUNDS = 3;

    /** What decides whether an existing container still matches the configuration. */
    private static final String SIGNATURE_FORMAT =
            "{{.HostConfig.NetworkMode}}|{{.Config.User}}|{{range .Mounts}}{{.Type}}={{.Source}};{{end}}";
    private static final Pattern WSL_SHARE = Pattern.compile("^//wsl(?:\\.localhost|\\$)/[^/]+(/.*)$", Pattern.CASE_INSENSITIVE);
    private static final String MAPPING_MARKER = ".codepal-mapping-check";
    /** Shared package-manager cache under the workspace base, mounted only into install containers. */
    static final String PACKAGE_CACHE = ".package-cache";
    /** Build context for the browser probe image, written from the classpath. */
    private static final String BROWSER_CONTEXT = ".browser-image";

    private final AgentProperties properties;
    private final WorkspaceStorage storage;
    private final CommandRunner runner;
    private final Clock clock;
    private final Map<UUID, Instant> lastUsed = new ConcurrentHashMap<>();
    /** Projects whose folder and running container this process has verified. */
    private final Set<UUID> ready = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ReentrantReadWriteLock> projectLocks = new ConcurrentHashMap<>();
    private final Instant startedAt;
    private volatile String browserImageTag;

    @Autowired
    public DockerProjectEnvironment(AgentProperties properties, WorkspaceStorage storage) {
        this(properties, storage, null, Clock.systemUTC());
    }

    /** For tests and compatibility constructors that only have the configuration. */
    public DockerProjectEnvironment(AgentProperties properties) {
        this(properties, new WorkspaceStorage(properties));
    }

    /** Test seam: {@code runner} receives Docker CLI arguments without the docker executable. */
    DockerProjectEnvironment(AgentProperties properties, WorkspaceStorage storage, CommandRunner runner, Clock clock) {
        this.properties = properties;
        this.storage = storage;
        this.runner = runner != null ? runner : this::runProcess;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /**
     * The backend and the Docker daemon must see the same project folders: the backend writes them and
     * containers bind-mount them. Write a random token through the backend's path and read it back in
     * a throwaway container through the daemon's path.
     */
    @Override
    public void verifyEnvironment() {
        Path base = storage.base();
        Path marker = base.resolve(MAPPING_MARKER);
        String token = UUID.randomUUID().toString();
        try {
            Files.createDirectories(base);
            Files.writeString(marker, token);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write to the project workspace " + base + ": " + error.getMessage(), error);
        }
        try {
            String dockerBase = dockerBase();
            CommandResult read = run(List.of("run", "--rm", "--network", "none", "--user", user(),
                    "--mount", "type=bind,source=" + dockerBase + ",target=/codepal-check,readonly",
                    properties.getSandbox().getImage(), "cat", "/codepal-check/" + MAPPING_MARKER), Duration.ofSeconds(180), null);
            if (!read.success() || !token.equals(read.stdout().trim())) {
                throw new IllegalStateException("The backend workspace (" + base + ") and the sandbox workspace (" + dockerBase
                        + ") are not the same folder, so containers would not see the project files. Point "
                        + "AGENT_WORKSPACE_PATH at the WSL share (\\\\wsl.localhost\\<distro>\\home\\<user>\\...), or set "
                        + "AGENT_SANDBOX_HOST_WORKSPACE_PATH to the same folder as Docker sees it. Docker said: "
                        + (read.error().isBlank() ? read.stdout().trim() : read.error()));
            }
            log.info("sandbox_workspace_verified backend={} docker={}", base, dockerBase);
        } finally {
            try { Files.deleteIfExists(marker); } catch (IOException ignored) { }
        }
    }

    /** The project base directory as the Docker daemon sees it. */
    String dockerBase() {
        String configured = properties.getSandbox().getHostWorkspacePath();
        if (configured != null && !configured.isBlank()) return configured.trim().replaceAll("/+$", "");
        String backend = storage.base().toString().replace('\\', '/');
        Matcher share = WSL_SHARE.matcher(backend);
        if (share.matches()) return share.group(1).replaceAll("/+$", "");
        if (!backend.matches("^[A-Za-z]:/.*") && backend.startsWith("/")) return backend;   // backend on Linux
        throw new IllegalStateException("Cannot bind-mount " + storage.base() + " into the sandbox: keep project files "
                + "in WSL (AGENT_WORKSPACE_PATH=\\\\wsl.localhost\\<distro>\\home\\<user>\\codepal\\workspaces) or set "
                + "AGENT_SANDBOX_HOST_WORKSPACE_PATH. Windows folders through /mnt/c are ~35x slower for builds.");
    }

    private String user() {
        String user = properties.getSandbox().getUser();
        return user == null || user.isBlank() ? "1000:1000" : user.trim();
    }

    @Override
    public void ensure(UUID projectId) {
        readyEnvironment(projectId);
    }

    /** Provisions the project container if needed and returns its Docker coordinates. */
    private Environment readyEnvironment(UUID projectId) {
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
        String container = environment.container();
        // Created by the backend, so the folder belongs to the sandbox user rather than to root (a
        // bind source Docker had to create itself would be root-owned and unwritable for both sides).
        storage.ensure(projectId);
        String expected = network() + "|" + user() + "|bind=" + environment.hostPath() + ";";
        CommandResult inspected = run(List.of("container", "inspect", "-f", SIGNATURE_FORMAT, container),
                Duration.ofSeconds(20), null);
        if (inspected.success() && !expected.equals(inspected.stdout().trim())) {
            // Created under an older configuration (named volume, network, or user). Only the container
            // is replaced; the project folder is untouched.
            log.info("sandbox_container_recreated projectId={} actual={} expected={}", projectId,
                    inspected.stdout().trim(), expected);
            run(List.of("rm", "-f", container), Duration.ofSeconds(30), null).requireSuccess();
            inspected = new CommandResult(false, 1, "", "removed: configuration changed");
        }
        if (!inspected.success()) {
            CommandResult created = run(List.of("run", "-d", "--name", container, "--label", WORKSPACE_LABEL,
                    "--label", "com.codepal.project=" + projectId,
                    "--network", network(),
                    "--user", user(),
                    "--cpus", Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                    "--memory", Math.max(256, properties.getSandbox().getMemoryMb()) + "m",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--mount", "type=bind,source=" + environment.hostPath() + ",target=" + properties.getSandbox().getWorkspacePath(),
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

    private String network() {
        String network = properties.getSandbox().getNetwork();
        return network == null || network.isBlank() ? "none" : network.trim();
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
                dockerBase() + "/" + projectId,
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
            Environment environment = readyEnvironment(projectId);
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
    @Override
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

    /** Execute a trusted backend command in a project-relative directory. */
    @Override
    public CommandResult exec(UUID projectId, String relativeRoot, List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Sandbox command is empty");
        return execInContainer(projectId, List.of("--workdir", rootPath(relativeRoot)), command,
                timeout(), null, outputLimit());
    }

    /**
     * A throwaway container on {@code install-network} with the project folder and a shared package
     * cache mounted; the project container itself never gets a network. Same user, limits and dropped
     * capabilities as the project container.
     */
    @Override
    public CommandResult install(UUID projectId, String relativeRoot, List<String> command) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Install command is empty");
        Environment environment = environment(projectId);
        storage.ensure(projectId);
        try {
            Files.createDirectories(storage.base().resolve(PACKAGE_CACHE));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot create the package cache: " + error.getMessage(), error);
        }
        String name = "codepal-install-" + UUID.randomUUID();
        List<String> args = new ArrayList<>(List.of("run", "--rm", "--name", name,
                "--label", "com.codepal.install=true", "--label", "com.codepal.project=" + projectId,
                "--network", installNetwork(), "--user", user(),
                "--cpus", Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                "--memory", Math.max(256, properties.getSandbox().getMemoryMb()) + "m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--mount", "type=bind,source=" + environment.hostPath() + ",target=" + properties.getSandbox().getWorkspacePath(),
                "--mount", "type=bind,source=" + dockerBase() + "/" + PACKAGE_CACHE + ",target=/package-cache",
                "-e", "HOME=/tmp", "-e", "npm_config_cache=/package-cache/npm", "-e", "npm_config_update_notifier=false",
                "--workdir", rootPath(relativeRoot), properties.getSandbox().getImage()));
        args.addAll(command);
        CommandResult result = run(args, Duration.ofMillis(Math.max(10_000, properties.getSandbox().getInstallTimeoutMs())),
                null, outputLimit());
        // Killing the docker CLI on timeout leaves the container running.
        if (result.timedOut()) run(List.of("rm", "-f", name), Duration.ofSeconds(30), null);
        return result;
    }

    @Override
    public void link(UUID projectId, String linkPath, String targetPath) {
        String link = ProjectPaths.normalize(linkPath, false);
        String target = ProjectPaths.relativeTarget(link, ProjectPaths.normalize(targetPath, false));
        exec(projectId, ".", List.of("sh", "-c", "rm -rf -- \"$1\" && ln -s -- \"$2\" \"$1\"", "link", link, target))
                .requireSuccess();
    }

    /**
     * A throwaway browser container with no network, the site folder mounted read-only, same user,
     * limits and dropped capabilities as the project container. Chromium runs without its own sandbox
     * inside it (that needs privileges the container does not have): the container is the boundary.
     */
    @Override
    public CommandResult browse(UUID projectId, String siteRoot) {
        String image;
        try {
            image = browserImage();
        } catch (RuntimeException error) {
            return new CommandResult(false, -1, "", CommandResult.NOT_STARTED + " (browser image): " + error.getMessage());
        }
        String root = ProjectPaths.normalize(siteRoot, true);
        String site = environment(projectId).hostPath() + (root.equals(".") ? "" : "/" + root);
        String name = "codepal-probe-" + UUID.randomUUID();
        List<String> args = List.of("run", "--rm", "--name", name, "--label", "com.codepal.probe=true",
                "--network", "none", "--user", user(),
                "--cpus", Double.toString(Math.max(0.5, properties.getSandbox().getCpus())),
                "--memory", Math.max(512, Math.min(2_048, properties.getSandbox().getMemoryMb())) + "m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--mount", "type=bind,source=" + site + ",target=/site,readonly", image, "/site");
        CommandResult result = run(args, Duration.ofMillis(Math.max(10_000, properties.getSandbox().getBrowserTimeoutMs())),
                null, outputLimit());
        if (result.timedOut()) run(List.of("rm", "-f", name), Duration.ofSeconds(30), null);
        return result;
    }

    /**
     * The probe image, built once from the classpath (Dockerfile + probe.mjs) under a tag derived from
     * their content, so a changed probe gets a new image. Building downloads Chromium.
     */
    private synchronized String browserImage() {
        if (browserImageTag != null) return browserImageTag;
        Map<String, byte[]> context = new java.util.LinkedHashMap<>();
        for (String file : List.of("Dockerfile", "probe.mjs")) {
            try (var input = getClass().getResourceAsStream("/sandbox/browser/" + file)) {
                if (input == null) throw new IllegalStateException("Missing classpath resource sandbox/browser/" + file);
                context.put(file, input.readAllBytes());
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read the browser probe: " + error.getMessage(), error);
            }
        }
        String tag = properties.getSandbox().getBrowserImage() + ":" + contentHash(context.values());
        if (run(List.of("image", "inspect", "--format", "{{.Id}}", tag), Duration.ofSeconds(20), null).success()) {
            return browserImageTag = tag;
        }
        Path directory = storage.base().resolve(BROWSER_CONTEXT);
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, byte[]> file : context.entrySet()) Files.write(directory.resolve(file.getKey()), file.getValue());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write the browser image context: " + error.getMessage(), error);
        }
        log.info("browser_image_build_started tag={}", tag);
        CommandResult built = run(List.of("build", "-q", "-t", tag, dockerBase() + "/" + BROWSER_CONTEXT),
                Duration.ofMillis(Math.max(60_000, properties.getSandbox().getBrowserImageBuildTimeoutMs())), null);
        if (!built.success()) throw new IllegalStateException("docker build failed: " + tail(built.stdout() + built.error()));
        log.info("browser_image_build_done tag={}", tag);
        return browserImageTag = tag;
    }

    private static String contentHash(java.util.Collection<byte[]> parts) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            parts.forEach(digest::update);
            return java.util.HexFormat.of().formatHex(digest.digest(), 0, 6);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 800 ? trimmed : "..." + trimmed.substring(trimmed.length() - 800);
    }

    private String installNetwork() {
        String network = properties.getSandbox().getInstallNetwork();
        return network == null || network.isBlank() ? "bridge" : network.trim();
    }

    @Override
    public String commandPath(UUID projectId, String relativeRoot) {
        return rootPath(relativeRoot);
    }

    /** Copies a subtree in one tar pipe next to the files (internal and secret folders excluded). */
    @Override
    public void copyTree(UUID projectId, String sourceRoot, String targetRoot) {
        String source = rootPath(sourceRoot);
        String target = rootPath(targetRoot);
        String parent = parentPath(target);
        String script = "rm -rf " + shellQuote(target) + " && mkdir -p " + shellQuote(target)
                + " && tar -C " + shellQuote(source) + " --exclude=.codepal --exclude=.git --exclude=.secrets --exclude=node_modules"
                + " -cf - . | tar -C " + shellQuote(target) + " -xf -";
        exec(projectId, ".", List.of("sh", "-lc", "mkdir -p " + shellQuote(parent) + " && " + script)).requireSuccess();
        // Internal state (staging, validation copies, history) is outside the quota by design.
        if (!ProjectPaths.isInternal(targetRoot)) enforceQuota(projectId);
    }

    @Override
    public void removeTree(UUID projectId, String relativeRoot) {
        String root = rootPath(relativeRoot);
        if ("/".equals(root) || root.equals(properties.getSandbox().getWorkspacePath())) {
            throw new IllegalArgumentException("Refusing to remove sandbox root");
        }
        exec(projectId, ".", List.of("rm", "-rf", "--", root)).requireSuccess();
    }

    /** Restore a complete project tree from an internal snapshot while preserving the snapshot store. */
    @Override
    public void restoreTree(UUID projectId, String snapshotRoot) {
        String source = rootPath(snapshotRoot);
        String workspace = properties.getSandbox().getWorkspacePath();
        // Nothing is removed unless the snapshot exists: a missing archive must not empty the project.
        String script = "test -d " + shellQuote(source)
                + " && find " + shellQuote(workspace) + " -mindepth 1 -maxdepth 1 ! -name .codepal -exec rm -rf -- {} +"
                + " && tar -C " + shellQuote(source) + " --exclude=.codepal -cf - . | tar -C "
                + shellQuote(workspace) + " -xf -";
        exec(projectId, ".", List.of("sh", "-lc", script)).requireSuccess();
        enforceQuota(projectId);
    }

    @Override
    public void destroy(UUID projectId) {
        String container = resourceName(properties.getSandbox().getContainerPrefix(), projectId);
        java.nio.file.Path folder = storage.projectDir(projectId);
        Lock lock = lockFor(projectId).writeLock();
        lock.lock();
        try {
            ready.remove(projectId);
            lastUsed.remove(projectId);
            if (java.nio.file.Files.isDirectory(folder)) {
                // Emptying the folder inside the container is far faster than deleting thousands of files
                // (node_modules) over the WSL share. Everything in it is written as the sandbox user.
                provision(projectId);
                CommandResult emptied = run(List.of("exec", container, "find",
                        properties.getSandbox().getWorkspacePath(), "-mindepth", "1", "-delete"), Duration.ofMinutes(5), null);
                if (!emptied.success()) log.warn("sandbox_destroy_partial projectId={} exit={} error={} output={}", projectId, emptied.exitCode(), emptied.error(), emptied.stdout());
            }
            run(List.of("rm", "-f", container), Duration.ofSeconds(30), null);
            storage.delete(projectId);   // the (now empty) folder itself, or whatever the container left
        } finally {
            lock.unlock();
        }
        if (java.nio.file.Files.exists(folder)) throw new IllegalStateException("Could not remove " + folder);
        projectLocks.remove(projectId);
        log.info("sandbox_destroyed projectId={}", projectId);
    }

    private String rootPath(String relativeRoot) {
        String root = ProjectPaths.normalize(relativeRoot, true);
        return root.equals(".") ? properties.getSandbox().getWorkspacePath()
                : properties.getSandbox().getWorkspacePath() + "/" + root;
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= properties.getSandbox().getWorkspacePath().length() ? properties.getSandbox().getWorkspacePath() : path.substring(0, slash);
    }

    private Duration timeout() { return Duration.ofMillis(Math.max(1_000, properties.getSandbox().getCommandTimeoutMs())); }

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
                return new CommandResult(false, -1, captured.toString(StandardCharsets.UTF_8), CommandResult.TIMED_OUT);
            }
            reader.join(1_000);
            return new CommandResult(process.exitValue() == 0, process.exitValue(), captured.toString(StandardCharsets.UTF_8), "");
        } catch (IOException e) {
            return new CommandResult(false, -1, "", CommandResult.NOT_STARTED + " (docker): " + e.getMessage());
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

    /** Docker coordinates of a project: container name and the bind-mount source as the daemon sees it. */
    public record Environment(UUID projectId, String container, String hostPath, String workspacePath) {}

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;
        private BoundedOutputStream(ByteArrayOutputStream delegate, int limit) { this.delegate = delegate; this.limit = Math.max(1, limit); }
        @Override public void write(int b) { if (delegate.size() < limit) delegate.write(b); }
        @Override public void write(byte[] bytes, int offset, int length) { if (delegate.size() < limit) delegate.write(bytes, offset, Math.min(length, limit - delegate.size())); }
    }
}
