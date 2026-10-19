package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.lease.TrustedOperationPendingException;
import com.code.pal.agent.lease.TrustedProjectIo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

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
    private TrustedProjectIo trustedIo;

    @Autowired
    void useTrustedIo(TrustedProjectIo io) {
        trustedIo = io;
    }

    private static final String WORKSPACE_LABEL = "com.codepal.workspace=true";
    /** Bounds re-checks when the container is stopped between ensure() and taking the read lock. */
    private static final int MAX_EXEC_ROUNDS = 3;

    /** What decides whether an existing container still matches the configuration. */
    private static final String SIGNATURE_FORMAT = "{{.HostConfig.NetworkMode}}|{{.Config.User}}|{{range .Mounts}}{{.Type}}={{.Source}};{{end}}";
    private static final Pattern WSL_SHARE = Pattern.compile("^//wsl(?:\\.localhost|\\$)/[^/]+(/.*)$",
            Pattern.CASE_INSENSITIVE);
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
        this.runner = runner != null ? runner : new DockerCommands(properties);
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
            throw new IllegalStateException("Cannot write to the project workspace " + base + ": " + error.getMessage(),
                    error);
        }
        try {
            String dockerBase = dockerBase();
            CommandResult read = run(List.of("run", "--rm", "--network", "none", "--user", user(),
                    "--mount", "type=bind,source=" + dockerBase + ",target=/codepal-check,readonly",
                    properties.getSandbox().getImage(), "cat", "/codepal-check/" + MAPPING_MARKER),
                    Duration.ofSeconds(180), null);
            if (!read.success() || !token.equals(read.stdout().trim())) {
                throw new IllegalStateException("The backend workspace (" + base + ") and the sandbox workspace ("
                        + dockerBase
                        + ") are not the same folder, so containers would not see the project files. Point "
                        + "AGENT_WORKSPACE_PATH at the WSL share (\\\\wsl.localhost\\<distro>\\home\\<user>\\...), or set "
                        + "AGENT_SANDBOX_HOST_WORKSPACE_PATH to the same folder as Docker sees it. Docker said: "
                        + (read.error().isBlank() ? read.stdout().trim() : read.error()));
            }
            log.info("sandbox_workspace_verified backend={} docker={}", base, dockerBase);
        } finally {
            try {
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
            }
        }
    }

    /** The project base directory as the Docker daemon sees it. */
    String dockerBase() {
        String configured = properties.getSandbox().getHostWorkspacePath();
        if (configured != null && !configured.isBlank())
            return configured.trim().replaceAll("/+$", "");
        String backend = storage.base().toString().replace('\\', '/');
        Matcher share = WSL_SHARE.matcher(backend);
        if (share.matches())
            return share.group(1).replaceAll("/+$", "");
        if (!backend.matches("^[A-Za-z]:/.*") && backend.startsWith("/"))
            return backend; // backend on Linux
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
                    "--mount",
                    "type=bind,source=" + environment.hostPath() + ",target="
                            + properties.getSandbox().getWorkspacePath(),
                    "--workdir", properties.getSandbox().getWorkspacePath(),
                    properties.getSandbox().getImage(), "sh", "-lc",
                    "while :; do sleep 3600; done"), Duration.ofSeconds(90), null);
            // Another backend process may have created it between inspect and run; that is fine.
            if (!created.success()) {
                if (!created.stdout().contains("is already in use"))
                    created.requireSuccess();
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
                primaryContainerName(projectId),
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
        if (RunCancellation.current().isCancelled())
            return new CommandResult(false, -1, "", CommandResult.CANCELLED);
        if (RunCancellation.current() != RunCancellation.NONE) {
            Environment environment = readyEnvironment(projectId);
            String name = "codepal-command-" + UUID.randomUUID();
            List<String> args = new ArrayList<>(List.of("run", "--name", name, "--label", "com.codepal.command=true",
                    "--label", "com.codepal.project=" + projectId, "--label",
                    "com.codepal.run=" + RunCancellation.current().runId(),
                    "--network", network(), "--user", user(), "--cpus",
                    Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                    "--memory", Math.max(256, properties.getSandbox().getMemoryMb()) + "m", "--pids-limit", "256",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--mount", "type=bind,source=" + environment.hostPath() + ",target=" + environment.workspacePath(),
                    "--workdir", environment.workspacePath()));
            args.addAll(options);
            args.add(properties.getSandbox().getImage());
            args.addAll(command);
            Lock lock = lockFor(projectId).readLock();
            lock.lock();
            try {
                return runOwnedContainer(name, args, timeout, stdin, outputLimit);
            } finally {
                lock.unlock();
            }
        }
        boolean retried = false;
        for (int round = 1;; round++) {
            Environment environment = readyEnvironment(projectId);
            List<String> args = new ArrayList<>();
            args.add("exec");
            args.addAll(options);
            args.add(environment.container());
            args.addAll(command);
            Lock lock = lockFor(projectId).readLock();
            CommandResult result;
            lock.lock();
            // Non-run trusted operations use the persistent container. Cancellable run commands are isolated above.
            try {
                // Idle cleanup may have stopped the container between ensure() and this lock.
                if (!ready.contains(projectId) && round < MAX_EXEC_ROUNDS)
                    continue;
                result = run(args, timeout, stdin, outputLimit);
            } finally {
                lock.unlock();
            }
            if (retried || !containerUnavailable(result))
                return result;
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
        if (result.success())
            return false;
        String output = result.stdout().stripLeading().toLowerCase(Locale.ROOT);
        if (!output.startsWith("error response from daemon"))
            return false;
        String firstLine = output.lines().findFirst().orElse("");
        return firstLine.contains("is not running") || firstLine.contains("no such container");
    }

    /** Keep running containers bounded while retaining project data in named volumes. */
    @Scheduled(fixedDelayString = "${app.agent.sandbox.cleanup-interval-ms:60000}")
    public void cleanupIdleSandboxes() {
        if (!properties.getSandbox().isRequired())
            return;
        discoverManagedContainers();
        Instant now = clock.instant();
        long stopAfter = Math.max(60_000, properties.getSandbox().getIdleStopMs());
        long removeAfter = Math.max(stopAfter, properties.getSandbox().getIdleRemoveMs());
        for (Map.Entry<UUID, Instant> entry : lastUsed.entrySet()) {
            if (!entry.getValue().equals(lastUsed.get(entry.getKey())))
                continue;
            long idleMs = Duration.between(entry.getValue(), now).toMillis();
            if (idleMs < stopAfter)
                continue;
            // The write lock waits for no one: a busy project (provisioning or running a command) is
            // in use, so skip it this round instead of blocking the shared scheduler thread.
            Lock lock = lockFor(entry.getKey()).writeLock();
            if (!lock.tryLock())
                continue;
            try {
                cleanupIdle(entry.getKey(), entry.getValue(), idleMs, removeAfter);
            } finally {
                lock.unlock();
            }
        }
    }

    private void cleanupIdle(UUID projectId, Instant seenLastUsed, long idleMs, long removeAfter) {
        String container = primaryContainerName(projectId);
        CommandResult status = run(List.of("container", "inspect", "-f", "{{.State.Status}}", container),
                Duration.ofSeconds(20), null);
        if (!status.success()) {
            ready.remove(projectId);
            lastUsed.remove(projectId, seenLastUsed);
            return;
        }
        if ("running".equalsIgnoreCase(status.stdout().trim())) {
            if (!seenLastUsed.equals(lastUsed.get(projectId)))
                return;
            ready.remove(projectId);
            run(List.of("stop", "-t", "10", container), Duration.ofSeconds(30), null);
            log.info("sandbox_container_stopped projectId={} idleMs={}", projectId, idleMs);
            return;
        }
        if (idleMs >= removeAfter) {
            if (!seenLastUsed.equals(lastUsed.get(projectId)))
                return;
            ready.remove(projectId);
            run(List.of("rm", container), Duration.ofSeconds(30), null);
            lastUsed.remove(projectId, seenLastUsed);
            log.info("sandbox_container_removed projectId={} idleMs={}", projectId, idleMs);
        }
    }

    private void discoverManagedContainers() {
        CommandResult containers = run(List.of("container", "ls", "-aq", "--filter", "label=" + WORKSPACE_LABEL),
                Duration.ofSeconds(20), null);
        if (!containers.success())
            return;
        for (String id : containers.stdout().split("\\R")) {
            if (id.isBlank())
                continue;
            CommandResult name = run(List.of("container", "inspect", "-f", "{{.Name}}", id.trim()),
                    Duration.ofSeconds(20), null);
            if (!name.success())
                continue;
            String value = name.stdout().trim().replaceFirst("^/", "");
            String prefix = properties.getSandbox().getContainerPrefix();
            if (!value.startsWith(prefix))
                continue;
            try {
                lastUsed.putIfAbsent(UUID.fromString(value.substring(prefix.length())), startedAt);
            } catch (IllegalArgumentException ignored) {
                /* ignore unrelated labelled containers */ }
        }
    }

    /** Enforce a project volume quota without counting internal snapshots/change sets. */
    @Override
    public void enforceQuota(UUID projectId) {
        long limit = properties.getSandbox().getMaxProjectBytes();
        if (limit <= 0)
            return;
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
        if (command == null || command.isEmpty())
            throw new IllegalArgumentException("Sandbox command is empty");
        String root = ProjectPaths.normalize(relativeRoot, true);
        if (root.equals(".")) {
            if (RunCancellation.current() != RunCancellation.NONE)
                throw new IllegalArgumentException("Run commands require a candidate root");
            return execInContainer(projectId, List.of("--workdir", rootPath(root)), command, timeout(), null,
                    outputLimit());
        }
        Environment environment = readyEnvironment(projectId);
        requirePlainCommandRoot(projectId, root);
        String name = "codepal-command-" + UUID.randomUUID();
        List<String> args = new ArrayList<>(List.of("run", "--name", name, "--label", "com.codepal.command=true",
                "--label", "com.codepal.project=" + projectId, "--label",
                "com.codepal.run=" + RunCancellation.current().runId(),
                "--network", network(), "--user", user(), "--cpus",
                Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                "--memory", Math.max(256, properties.getSandbox().getMemoryMb()) + "m", "--pids-limit", "256",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only", "--tmpfs",
                "/tmp:rw,nosuid,size=256m",
                "--mount", "type=bind,source=" + environment.hostPath() + "/" + root + ",target=" + rootPath(root),
                "--workdir", rootPath(root)));
        // Relative node_modules links may reach this readonly cache; accepted files/history/secrets stay unmounted.
        if (Files.isDirectory(storage.projectDir(projectId).resolve(".codepal/deps"), LinkOption.NOFOLLOW_LINKS)) {
            requirePlainCommandRoot(projectId, ".codepal/deps");
            args.addAll(List.of("--mount", "type=bind,source=" + environment.hostPath() + "/.codepal/deps,target="
                    + rootPath(".codepal/deps") + ",readonly"));
            addBuildCacheMounts(projectId, args);
        }
        args.add(properties.getSandbox().getImage());
        args.addAll(command);
        Lock lock = lockFor(projectId).readLock();
        lock.lock();
        try {
            return runOwnedContainer(name, args, timeout(), null, outputLimit());
        } finally {
            lock.unlock();
        }
    }

    /** Per-command writable scratch over immutable dependencies; Vite bundles config in .vite-temp. */
    private void addBuildCacheMounts(UUID projectId, List<String> args) {
        Path deps = storage.projectDir(projectId).resolve(".codepal/deps");
        try (var sets = Files.list(deps)) {
            for (Path set : sets.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                if (!set.getFileName().toString().matches("(?:[a-f0-9]{32}|[a-f0-9]{64})"))
                    continue;
                Path modules = set.resolve("node_modules");
                if (!Files.isDirectory(modules, LinkOption.NOFOLLOW_LINKS))
                    continue;
                for (String name : List.of(".vite-temp", ".vite", ".cache")) {
                    Path cache = modules.resolve(name);
                    if (Files.isSymbolicLink(cache))
                        throw new IllegalStateException("Unsafe dependency cache link");
                    Files.createDirectories(cache);
                    args.addAll(
                            List.of("--tmpfs", rootPath(".codepal/deps/" + set.getFileName() + "/node_modules/" + name)
                                    + ":rw,nosuid,nodev,size=64m,mode=1777"));
                }
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot prepare isolated build caches", failed);
        }
    }

    private CommandResult trustedTreeCommand(UUID project, List<String> command) {
        Environment env = readyEnvironment(project);
        String name = "codepal-trusted-" + UUID.randomUUID();
        List<String> args = new ArrayList<>(List.of("run", "--name", name, "--label", "com.codepal.trusted=true",
                "--label", "com.codepal.project=" + project, "--network", "none", "--user", user(),
                "--cpus", Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                "--memory", Math.max(256, properties.getSandbox().getMemoryMb()) + "m", "--pids-limit", "256",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--mount",
                "type=bind,source=" + env.hostPath() + ",target=" + env.workspacePath(), "--workdir",
                env.workspacePath(), properties.getSandbox().getImage()));
        args.addAll(command);
        UUID operation = trustedIo == null ? null : trustedIo.begin(project, name, daemonIdentity());
        Lock lock = lockFor(project).readLock();
        lock.lock();
        try {
            return runOwnedContainer(name, args, timeout(), null, outputLimit());
        } finally {
            try {
                if (!removeContainerQuietly(name))
                    throw new TrustedOperationPendingException();
                if (operation != null)
                    trustedIo.finish(operation);
            } finally {
                lock.unlock();
            }
        }
    }

    private String daemonIdentity() {
        CommandResult identity = run(List.of("info", "--format", "{{.ID}}"), Duration.ofSeconds(5), null);
        identity.requireSuccess();
        String id = identity.stdout().trim();
        if (!id.matches("[A-Za-z0-9:-]{6,100}"))
            throw new IllegalStateException("Docker daemon identity is unavailable");
        return id;
    }

    @Override
    public boolean reconcilePendingOperations(UUID project) {
        if (trustedIo == null)
            return true;
        String daemon = daemonIdentity();
        for (var pending : trustedIo.pending(project)) {
            if (!daemon.equals(pending.daemon()))
                return false; // never certify absence on a different daemon
            CommandResult inspected = run(
                    List.of("container", "inspect", "-f", "{{json .Config.Labels}}", pending.container()),
                    Duration.ofSeconds(10), null);
            if (!inspected.success()) {
                if (noSuchContainer(inspected)) {
                    trustedIo.finish(pending.id());
                    continue;
                }
                return false;
            }
            var labels = new ObjectMapper().readTree(inspected.stdout());
            if (!project.toString().equals(labels.path("com.codepal.project").asText())
                    || !labels.path("com.codepal.trusted").asText().equals("true"))
                throw new IllegalStateException("Pending operation ownership does not match the container");
            if (!removeContainerQuietly(pending.container()))
                return false;
            trustedIo.finish(pending.id());
        }
        return trustedIo.pending(project).isEmpty();
    }

    private void requirePlainCommandRoot(UUID project, String root) {
        Path base = storage.projectDir(project).toAbsolutePath().normalize();
        Path path = base;
        for (String part : root.split("/")) {
            path = path.resolve(part);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Command root contains a link or is not a directory");
        }
    }

    /**
     * A throwaway container on {@code install-network} with the project folder and a shared package
     * cache mounted; the project container itself never gets a network. Same user, limits and dropped
     * capabilities as the project container.
     */
    @Override
    public CommandResult install(UUID projectId, String relativeRoot, List<String> command) {
        if (command == null || command.isEmpty())
            throw new IllegalArgumentException("Install command is empty");
        Environment environment = environment(projectId);
        storage.ensure(projectId);
        String isolatedRoot = ProjectPaths.normalize(relativeRoot, true);
        if (isolatedRoot.equals("."))
            throw new IllegalArgumentException("Installs require an isolated dependency root");
        requirePlainCommandRoot(projectId, isolatedRoot);
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
                "--mount",
                "type=bind,source=" + environment.hostPath() + "/" + isolatedRoot + ",target=" + rootPath(isolatedRoot),
                "--mount", "type=bind,source=" + dockerBase() + "/" + PACKAGE_CACHE + ",target=/package-cache",
                "-e", "HOME=/tmp", "-e", "npm_config_cache=/package-cache/npm", "-e",
                "npm_config_update_notifier=false",
                // pnpm and yarn come through corepack; their caches share the package cache, and yarn 2+ is
                // told to use node_modules, skip build scripts and accept a changed lockfile.
                "-e", "COREPACK_HOME=/package-cache/corepack", "-e", "COREPACK_ENABLE_DOWNLOAD_PROMPT=0",
                "-e", "COREPACK_ENABLE_STRICT=0", "-e", "npm_config_store_dir=/package-cache/pnpm",
                "-e", "YARN_CACHE_FOLDER=/package-cache/yarn", "-e", "YARN_NODE_LINKER=node-modules",
                "-e", "YARN_ENABLE_SCRIPTS=false", "-e", "YARN_ENABLE_IMMUTABLE_INSTALLS=false",
                "-e", "YARN_ENABLE_TELEMETRY=0", "-e", "YARN_ENABLE_GLOBAL_CACHE=false",
                "--workdir", rootPath(relativeRoot), properties.getSandbox().getImage()));
        args.addAll(command);
        return runOwnedContainer(name, args,
                Duration.ofMillis(Math.max(10_000, properties.getSandbox().getInstallTimeoutMs())), null,
                outputLimit());
    }

    @Override
    public void link(UUID projectId, String linkPath, String targetPath) {
        String link = ProjectPaths.normalize(linkPath, false);
        String target = ProjectPaths.relativeTarget(link, ProjectPaths.normalize(targetPath, false));
        trustedTreeCommand(projectId,
                List.of("sh", "-c", "rm -rf -- \"$1\" && ln -s -- \"$2\" \"$1\"", "link", link, target))
                .requireSuccess();
    }

    /**
     * A throwaway browser container with no network, the site folder mounted read-only, same user,
     * limits and dropped capabilities as the project container. Chromium runs without its own sandbox
     * inside it (that needs privileges the container does not have): the container is the boundary.
     */
    @Override
    public CommandResult browse(UUID projectId, String siteRoot) {
        return browseInternal(projectId, siteRoot, null, null);
    }

    @Override
    public CommandResult browse(UUID projectId, String siteRoot, String acceptanceJson) {
        if (acceptanceJson == null || acceptanceJson.length() > 24_000)
            throw new IllegalArgumentException("Invalid browser acceptance size");
        return browseInternal(projectId, siteRoot, acceptanceJson, null);
    }

    @Override
    public CommandResult browseWithApi(UUID projectId, String siteRoot, String acceptanceJson,
            ServerEndpoint candidate) {
        return browseWithRelay(projectId, siteRoot, acceptanceJson, candidate, BrowserApiRelay.Scope.API);
    }

    @Override
    public CommandResult browseWithSupabase(UUID projectId, String siteRoot, String acceptanceJson, ServerEndpoint scratch) {
        return browseWithRelay(projectId, siteRoot, acceptanceJson, scratch, BrowserApiRelay.Scope.SUPABASE);
    }

    private CommandResult browseWithRelay(UUID projectId, String siteRoot, String acceptanceJson,
            ServerEndpoint candidate, BrowserApiRelay.Scope scope) {
        if (acceptanceJson == null || acceptanceJson.length() > 24_000)
            throw new IllegalArgumentException("Invalid browser acceptance size");
        String parent = ".codepal/browser-relay";
        Path root = storage.projectDir(projectId).resolve(parent);
        try {
            requirePlainCommandRoot(projectId, ".");
            Path internal = storage.projectDir(projectId).resolve(".codepal");
            if (Files.isSymbolicLink(internal) || Files.isSymbolicLink(root))
                throw new IllegalArgumentException("Unsafe relay root");
            Files.createDirectories(root);
            requirePlainCommandRoot(projectId, parent);
            String id = UUID.randomUUID().toString();
            try (var relay = new BrowserApiRelay(root.resolve(id), candidate, scope)) {
                return browseInternal(projectId, siteRoot, acceptanceJson, parent + "/" + id, scope);
            }
        } catch (IOException unavailable) {
            throw new IllegalStateException("Candidate browser relay unavailable", unavailable);
        }
    }

    private CommandResult browseInternal(UUID projectId, String siteRoot, String acceptanceJson, String relayRoot) {
        return browseInternal(projectId, siteRoot, acceptanceJson, relayRoot, BrowserApiRelay.Scope.API);
    }

    private CommandResult browseInternal(UUID projectId, String siteRoot, String acceptanceJson, String relayRoot, BrowserApiRelay.Scope scope) {
        String image;
        try {
            image = browserImage();
        } catch (RuntimeException error) {
            return new CommandResult(false, -1, "",
                    CommandResult.NOT_STARTED + " (browser image): " + error.getMessage());
        }
        String root = ProjectPaths.normalize(siteRoot, true);
        String site = environment(projectId).hostPath() + (root.equals(".") ? "" : "/" + root);
        String name = "codepal-probe-" + UUID.randomUUID();
        List<String> args = new ArrayList<>(List.of("run", "--rm", "--name", name, "--label", "com.codepal.probe=true",
                "--label", "com.codepal.project=" + projectId,
                "--network", "none", "--user", user(),
                "--cpus", Double.toString(Math.max(0.5, properties.getSandbox().getCpus())),
                "--memory", Math.max(512, Math.min(2_048, properties.getSandbox().getMemoryMb())) + "m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--mount", "type=bind,source=" + site + ",target=/site,readonly"));
        if (relayRoot != null)
            args.addAll(List.of("--mount",
                    "type=bind,source=" + environment(projectId).hostPath() + "/" + relayRoot + ",target=/relay"));
        if (scope == BrowserApiRelay.Scope.SUPABASE) {
            args.addAll(List.of("-e", "CODEPAL_SUPABASE_ORIGIN=" + DockerSupabaseScratch.BROWSER_ORIGIN));
        }
        if (properties.getVisualReview().isEnabled() && RunCancellation.current().runId() != null) {
            String evidence = prepareBrowserEvidence(projectId);
            args.addAll(List.of("--mount", "type=bind,source=" + environment(projectId).hostPath()
                    + "/" + evidence + ",target=/evidence", "-e", "CODEPAL_BROWSER_EVIDENCE_DIR=/evidence"));
        }
        args.addAll(List.of(image, "/site"));
        if (acceptanceJson != null)
            args.addAll(List.of("1500",
                    Base64.getEncoder().encodeToString(acceptanceJson.getBytes(StandardCharsets.UTF_8))));
        if (relayRoot != null)
            args.add("/relay");
        return runOwnedContainer(name, args,
                Duration.ofMillis(Math.max(10_000, properties.getSandbox().getBrowserTimeoutMs())), null,
                outputLimit());
    }

    /** At most two current captures per project; no image history or publicly served files. */
    private String prepareBrowserEvidence(UUID projectId) {
        String relative = ".codepal/browser-evidence/current";
        try {
            requirePlainCommandRoot(projectId, ".");
            Path parent = storage.projectDir(projectId);
            for (String segment : relative.split("/")) {
                parent = parent.resolve(segment);
                if (Files.isSymbolicLink(parent) || Files.exists(parent) && !Files.isDirectory(parent)) {
                    throw new IllegalStateException("Unsafe browser evidence directory");
                }
                Files.createDirectories(parent);
            }
            requirePlainCommandRoot(projectId, relative);
            for (String image : List.of("desktop.png", "mobile.png")) {
                Path target = parent.resolve(image);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Unsafe browser evidence file");
                }
                Files.deleteIfExists(target);
            }
            return relative;
        } catch (IOException failure) {
            throw new IllegalStateException("Browser evidence directory unavailable", failure);
        }
    }

    /**
     * The probe image, built once from the classpath (Dockerfile + probe.mjs) under a tag derived from
     * their content, so a changed probe gets a new image. Building downloads Chromium.
     */
    private synchronized String browserImage() {
        if (browserImageTag != null)
            return browserImageTag;
        return browserImageTag = classpathImage("browser", List.of("Dockerfile", "probe.mjs", "relay.mjs", "outcomes.mjs"),
                properties.getSandbox().getBrowserImage(), BROWSER_CONTEXT);
    }

    /** An image built from {@code sandbox/<resource>/} on the classpath, tagged by its content. */
    private synchronized String classpathImage(String resource, List<String> fileNames, String repository,
            String contextDirectory) {
        Map<String, byte[]> context = new LinkedHashMap<>();
        for (String file : fileNames) {
            try (var input = getClass().getResourceAsStream("/sandbox/" + resource + "/" + file)) {
                if (input == null)
                    throw new IllegalStateException("Missing classpath resource sandbox/" + resource + "/" + file);
                context.put(file, input.readAllBytes());
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read sandbox/" + resource + ": " + error.getMessage(), error);
            }
        }
        String tag = repository + ":" + contentHash(context.values());
        if (run(List.of("image", "inspect", "--format", "{{.Id}}", tag), Duration.ofSeconds(20), null).success())
            return tag;
        Path directory = storage.base().resolve(contextDirectory);
        try {
            Files.createDirectories(directory);
            for (Map.Entry<String, byte[]> file : context.entrySet())
                Files.write(directory.resolve(file.getKey()), file.getValue());
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write the image build context: " + error.getMessage(), error);
        }
        log.info("image_build_started tag={}", tag);
        CommandResult built = run(List.of("build", "-q", "-t", tag, dockerBase() + "/" + contextDirectory),
                Duration.ofMillis(Math.max(60_000, properties.getSandbox().getBrowserImageBuildTimeoutMs())), null);
        if (!built.success())
            throw new IllegalStateException("docker build failed: " + tail(built.stdout() + built.error()));
        log.info("image_build_done tag={}", tag);
        return tag;
    }

    private static String contentHash(Collection<byte[]> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            parts.forEach(digest::update);
            return HexFormat.of().formatHex(digest.digest(), 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
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
                + " && tar -C " + shellQuote(source)
                + " --exclude=.codepal --exclude=.git --exclude=.secrets --exclude=node_modules"
                + " -cf - . | tar -C " + shellQuote(target) + " -xf -";
        trustedTreeCommand(projectId,
                List.of("bash", "-o", "pipefail", "-lc", "mkdir -p " + shellQuote(parent) + " && " + script))
                .requireSuccess();
        // Internal state (staging, validation copies, history) is outside the quota by design.
        if (!ProjectPaths.isInternal(targetRoot))
            enforceQuota(projectId);
    }

    @Override
    public void removeTree(UUID projectId, String relativeRoot) {
        String root = rootPath(relativeRoot);
        if ("/".equals(root) || root.equals(properties.getSandbox().getWorkspacePath())) {
            throw new IllegalArgumentException("Refusing to remove sandbox root");
        }
        trustedTreeCommand(projectId, List.of("rm", "-rf", "--", root)).requireSuccess();
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
        trustedTreeCommand(projectId, List.of("bash", "-o", "pipefail", "-lc", script)).requireSuccess();
        enforceQuota(projectId);
    }

    @Override
    public void destroy(UUID projectId) {
        String container = primaryContainerName(projectId);
        Path folder = storage.projectDir(projectId);
        Lock lock = lockFor(projectId).writeLock();
        lock.lock();
        try {
            ready.remove(projectId);
            lastUsed.remove(projectId);
            if (Files.isDirectory(folder)) {
                // Emptying the folder inside the container is far faster than deleting thousands of files
                // (node_modules) over the WSL share. Everything in it is written as the sandbox user.
                provision(projectId);
                CommandResult emptied = run(List.of("exec", container, "find",
                        properties.getSandbox().getWorkspacePath(), "-mindepth", "1", "-delete"), Duration.ofMinutes(5),
                        null);
                if (!emptied.success())
                    log.warn("sandbox_destroy_partial projectId={} exit={} error={} output={}", projectId,
                            emptied.exitCode(), emptied.error(), emptied.stdout());
            }
            run(List.of("rm", "-f", container), Duration.ofSeconds(30), null);
            CommandResult remaining = run(List.of("ps", "-aq", "--filter", "name=^/" + container + "$"),
                    Duration.ofSeconds(20), null);
            remaining.requireSuccess();
            if (!remaining.stdout().isBlank())
                throw new IllegalStateException("Project sandbox cleanup is incomplete");
            removeAppServers(projectId);
            removeProjectNetworks(projectId);
            storage.delete(projectId); // the (now empty) folder itself, or whatever the container left
        } finally {
            lock.unlock();
        }
        if (Files.exists(folder))
            throw new IllegalStateException("Could not remove " + folder);
        projectLocks.remove(projectId);
        log.info("sandbox_destroyed projectId={}", projectId);
    }

    // ---- App servers (MERN APIs): internal network + trusted gateway --------------------------------

    private static final String APP_LABEL = "com.codepal.app=true";
    private static final String GATEWAY_CONTEXT = ".gateway-image";
    private static final HttpClient GATEWAY_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private volatile AppsNetwork appsNetwork;

    private String appsDatabaseContainer() {
        return helperName(properties.getSandbox().getAppsDatabaseContainer());
    }

    private String gatewayContainer() {
        return helperName(properties.getSandbox().getGatewayContainer());
    }

    private static String helperName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9_.-]{0,95}"))
            throw new IllegalArgumentException("Invalid helper resource name");
        return name;
    }

    /** The internal network's subnet and the apps' database container's address on it. */
    private record AppsNetwork(String subnet, String database) {
    }

    @Override
    public String databaseAddress() {
        readyGateway();
        return appsDatabaseContainer() + ":27017";
    }

    @Override
    public String databaseAdminUri() {
        readyGateway();
        return "mongodb://root:" + appsDatabasePassword() + "@" + properties.getSandbox().getGatewayHost() + ":"
                + properties.getSandbox().getAppsDatabasePort() + "/?authSource=admin&directConnection=true";
    }

    /** The apps' MongoDB on the internal network: authentication on, data in its own volume. Returns its IP. */
    private String readyAppsDatabase() {
        String format = "{{.State.Running}} {{json .NetworkSettings.Networks}}";
        CommandResult state = run(List.of("container", "inspect", "-f", format, appsDatabaseContainer()),
                Duration.ofSeconds(20), null);
        if (!state.success()) {
            String image = properties.getSandbox().getAppsDatabaseImage();
            if (!run(List.of("image", "inspect", "--format", "{{.Id}}", image), Duration.ofSeconds(20), null)
                    .success()) {
                log.info("apps_database_image_pull image={}", image);
                CommandResult pulled = run(List.of("pull", "-q", image), Duration.ofMinutes(15), null);
                if (!pulled.success())
                    throw new IllegalStateException("Could not download the apps database image " + image
                            + ": " + tail(pulled.error().isBlank() ? pulled.stdout() : pulled.error()));
            }
            log.info("apps_database_creating image={}", image);
            run(List.of("run", "-d", "--name", appsDatabaseContainer(), "--restart", "unless-stopped",
                    "--network", properties.getSandbox().getAppsNetwork(), "--label", "com.codepal.apps-database=true",
                    "--memory", Math.max(256, properties.getSandbox().getAppsDatabaseMemoryMb()) + "m",
                    "--mount",
                    "type=volume,source=" + helperName(properties.getSandbox().getAppsDatabaseVolume())
                            + ",target=/data/db",
                    "-e", "MONGO_INITDB_ROOT_USERNAME=root", "-e",
                    "MONGO_INITDB_ROOT_PASSWORD=" + appsDatabasePassword(),
                    properties.getSandbox().getAppsDatabaseImage(), "--wiredTigerCacheSizeGB", "0.25"),
                    Duration.ofMinutes(10), null).requireSuccess();
        } else if (!state.stdout().trim().startsWith("true")) {
            run(List.of("start", appsDatabaseContainer()), Duration.ofSeconds(30), null).requireSuccess();
        }
        String[] parts = run(List.of("container", "inspect", "-f", format, appsDatabaseContainer()),
                Duration.ofSeconds(20), null)
                .requireSuccess().stdout().trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank())
            throw new IllegalStateException("The apps database has no address");
        var networks = new ObjectMapper().readTree(parts[1]);
        String address = networks.path(properties.getSandbox().getAppsNetwork()).path("IPAddress").asString("");
        if (address.isBlank())
            throw new IllegalStateException("The apps database has no trusted admin-network address");
        return address;
    }

    /** Generated once and kept next to the projects (the backend's view of the workspace base). */
    private synchronized String appsDatabasePassword() {
        String configured = properties.getSandbox().getAppsDatabasePassword();
        if (configured != null && !configured.isBlank())
            return configured.trim();
        Path secret = storage.base().resolve(".apps-database-secret");
        try {
            if (Files.exists(secret))
                return Files.readString(secret).trim();
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String password = HexFormat.of().formatHex(random);
            Files.createDirectories(secret.getParent());
            Files.writeString(secret, password);
            return password;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read or create the apps database password: " + error.getMessage(),
                    error);
        }
    }

    @Override
    public ServerEndpoint startServer(UUID projectId, String name, String relativeRoot, List<String> command,
            Map<String, String> variables, int port) {
        String isolatedRoot = ProjectPaths.normalize(relativeRoot, true);
        if (isolatedRoot.equals("."))
            throw new IllegalArgumentException("Servers require an isolated release root");
        requirePlainCommandRoot(projectId, isolatedRoot);
        readyGateway();
        String privateNetwork = projectNetwork(projectId, name);
        Environment env = environment(projectId);
        String container = appContainer(projectId, name);
        run(List.of("rm", "-f", container), Duration.ofSeconds(30), null);
        List<String> args = new ArrayList<>(List.of("run", "-d", "--name", container,
                "--label", APP_LABEL, "--label", "com.codepal.project=" + projectId, "--label",
                "com.codepal.port=" + port, "--label", "com.codepal.namespace=" + workspaceNamespace(),
                "--network", privateNetwork, "--user", user(),
                "--cpus", Double.toString(Math.max(0.25, properties.getSandbox().getCpus())),
                "--memory", Math.max(128, properties.getSandbox().getServerMemoryMb()) + "m", "--pids-limit", "256",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only", "--tmpfs",
                "/tmp:rw,size=64m",
                "--mount",
                "type=bind,source=" + env.hostPath() + "/" + isolatedRoot + ",target=" + rootPath(isolatedRoot)
                        + ",readonly",
                "--workdir", rootPath(relativeRoot), "-e", "HOME=/tmp", "-e", "PORT=" + port));
        if (Files.isDirectory(storage.projectDir(projectId).resolve(".codepal/deps"), LinkOption.NOFOLLOW_LINKS)) {
            requirePlainCommandRoot(projectId, ".codepal/deps");
            args.addAll(List.of("--mount", "type=bind,source=" + env.hostPath() + "/.codepal/deps,target="
                    + rootPath(".codepal/deps") + ",readonly"));
        }
        variables.forEach((key, value) -> {
            args.add("-e");
            args.add(key + "=" + value);
        });
        args.add(properties.getSandbox().getImage());
        args.addAll(command);
        run(args, Duration.ofSeconds(60), null).requireSuccess();
        ServerEndpoint endpoint = server(projectId, name);
        if (endpoint == null)
            throw new IllegalStateException("The app server exited at once: " + tail(serverLogs(projectId, name, 40)));
        log.info("app_server_started projectId={} name={}", projectId, name);
        return endpoint;
    }

    @Override
    public ServerEndpoint server(UUID projectId, String name) {
        // No quotes in the format: they do not survive the Windows command line on the way to wsl.exe.
        CommandResult inspected = run(List.of("container", "inspect", "-f",
                "{{.State.Running}} {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}} {{json .Config.Labels}}",
                appContainer(projectId, name)), Duration.ofSeconds(20), null);
        if (!inspected.success())
            return null;
        String[] parts = inspected.stdout().trim().split("\\s+", 3);
        String port = parts.length < 3 ? null : label(parts[2], "com.codepal.port");
        if (port == null || !"true".equals(parts[0]) || parts[1].isBlank())
            return null;
        if (!projectId.toString().equals(label(parts[2], "com.codepal.project"))
                || !workspaceNamespace().equals(label(parts[2], "com.codepal.namespace")))
            throw new IllegalStateException("App server namespace is unverified");
        readyGateway();
        String upstream = parts[1] + ":" + port;
        long expiry = System.currentTimeMillis() / 1000 + 600;
        return new ServerEndpoint(
                "http://" + properties.getSandbox().getGatewayHost() + ":" + properties.getSandbox().getGatewayPort(),
                Map.of("x-codepal-upstream", upstream, "x-codepal-expires", String.valueOf(expiry),
                        "x-codepal-signature", signUpstream(upstream, expiry)));
    }

    @Override
    public void stopServer(UUID projectId, String name) {
        String container = appContainer(projectId, name);
        List<String> lookup = List.of("ps", "-aq", "--filter", "name=^/" + container + "$");
        var present = run(lookup, Duration.ofSeconds(20), null);
        present.requireSuccess();
        if (present.stdout().isBlank()) {
            removeServerNetwork(projectId, name);
            return;
        }
        var ownership = run(List.of("container", "inspect", "-f", "{{json .Config.Labels}}", container),
                Duration.ofSeconds(20), null);
        ownership.requireSuccess();
        if (!projectId.toString().equals(label(ownership.stdout(), "com.codepal.project"))
                || !"true".equals(label(ownership.stdout(), "com.codepal.app"))
                || !workspaceNamespace().equals(label(ownership.stdout(), "com.codepal.namespace")))
            throw new IllegalStateException("Candidate server ownership could not be verified");
        run(List.of("rm", "-f", container), Duration.ofSeconds(30), null).requireSuccess();
        var remaining = run(lookup, Duration.ofSeconds(20), null);
        remaining.requireSuccess();
        if (!remaining.stdout().isBlank())
            throw new IllegalStateException("Candidate server cleanup needs verification");
        removeServerNetwork(projectId, name);
    }

    @Override
    public String serverLogs(UUID projectId, String name, int lines) {
        CommandResult logs = run(
                List.of("logs", "--tail", Integer.toString(Math.max(1, lines)), appContainer(projectId, name)),
                Duration.ofSeconds(20), null);
        return (logs.stdout() + (logs.error().isBlank() ? "" : "\n" + logs.error())).strip();
    }

    private void removeAppServers(UUID projectId) {
        CommandResult listed = run(
                List.of("ps", "-aq", "--filter", "label=" + APP_LABEL, "--filter",
                        "label=com.codepal.project=" + projectId, "--filter",
                        "label=com.codepal.namespace=" + workspaceNamespace()),
                Duration.ofSeconds(20), null);
        listed.requireSuccess();
        for (String id : listed.stdout().split("\\R"))
            if (!id.isBlank())
                run(List.of("rm", "-f", id.trim()), Duration.ofSeconds(30), null).requireSuccess();
        var remaining = run(List.of("ps", "-aq", "--filter", "label=" + APP_LABEL, "--filter",
                "label=com.codepal.project=" + projectId, "--filter",
                "label=com.codepal.namespace=" + workspaceNamespace()), Duration.ofSeconds(20), null);
        remaining.requireSuccess();
        if (!remaining.stdout().isBlank())
            throw new IllegalStateException("Project app process cleanup is incomplete");
    }

    private String appContainer(UUID projectId, String name) {
        if (!name.matches("[a-z0-9-]{1,40}"))
            throw new IllegalArgumentException("Invalid server name");
        return "codepal-app-" + workspaceNamespace() + "-" + projectId + "-" + name;
    }

    String workspaceNamespace() {
        return WorkspaceNamespace.of(storage.base());
    }

    String primaryContainerName(UUID project) {
        return resourceName(properties.getSandbox().getContainerPrefix() + workspaceNamespace() + "-", project);
    }

    private String projectNetworkName(UUID project, String server) {
        if (!server.matches("[a-z0-9-]{1,40}"))
            throw new IllegalArgumentException("Invalid server name");
        String prefix = helperName(properties.getSandbox().getAppsNetwork());
        return prefix.substring(0, Math.min(20, prefix.length())) + "-" + workspaceNamespace() + "-" + project + "-"
                + server;
    }

    private synchronized String gatewaySecret() {
        Path path = storage.base().resolve(".apps-gateway-secret");
        try {
            if (Files.exists(path)) {
                String value = Files.readString(path).strip();
                if (!value.matches("[a-f0-9]{64}"))
                    throw new IllegalStateException("Invalid trusted relay key");
                return value;
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String value = HexFormat.of().formatHex(bytes);
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardOpenOption.CREATE_NEW);
            return value;
        } catch (IOException unavailable) {
            throw new IllegalStateException("Trusted relay key is unavailable", unavailable);
        }
    }

    private String signUpstream(String upstream, long expiry) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(gatewaySecret()), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((upstream + "\n" + expiry).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException invalid) {
            throw new IllegalStateException("Trusted relay signature unavailable", invalid);
        }
    }

    private synchronized String projectNetwork(UUID project, String server) {
        String name = projectNetworkName(project, server);
        var found = run(List.of("network", "inspect", "-f", "{{.Internal}} {{json .Labels}}", name),
                Duration.ofSeconds(20), null);
        if (!found.success()) {
            run(List.of("network", "create", "--internal", "--label", "com.codepal.project=" + project, "--label",
                    "com.codepal.namespace=" + workspaceNamespace(), "--label", "com.codepal.server=" + server, name),
                    Duration.ofSeconds(30), null).requireSuccess();
            found = run(List.of("network", "inspect", "-f", "{{.Internal}} {{json .Labels}}", name),
                    Duration.ofSeconds(20), null);
        }
        found.requireSuccess();
        String[] proof = found.stdout().strip().split("\\s+", 2);
        if (proof.length != 2 || !"true".equals(proof[0])
                || !project.toString().equals(label(proof[1], "com.codepal.project"))
                || !workspaceNamespace().equals(label(proof[1], "com.codepal.namespace"))
                || !server.equals(label(proof[1], "com.codepal.server")))
            throw new IllegalStateException("Project network ownership is unverified");
        var attached = run(
                List.of("container", "inspect", "-f", "{{json .NetworkSettings.Networks}}", appsDatabaseContainer()),
                Duration.ofSeconds(20), null);
        attached.requireSuccess();
        if (!new ObjectMapper().readTree(attached.stdout()).has(name))
            run(List.of("network", "connect", "--alias", appsDatabaseContainer(), name, appsDatabaseContainer()),
                    Duration.ofSeconds(30), null).requireSuccess();
        return name;
    }

    private void removeServerNetwork(UUID project, String server) {
        String name = projectNetworkName(project, server);
        var present = run(List.of("network", "ls", "-q", "--filter", "name=^" + name + "$"), Duration.ofSeconds(20),
                null);
        present.requireSuccess();
        if (present.stdout().isBlank())
            return;
        removeOwnedNetwork(project, name);
    }

    private void removeProjectNetworks(UUID project) {
        var listed = run(List.of("network", "ls", "-q", "--filter", "label=com.codepal.project=" + project, "--filter",
                "label=com.codepal.namespace=" + workspaceNamespace()), Duration.ofSeconds(20), null);
        listed.requireSuccess();
        for (String id : listed.stdout().split("\\R"))
            if (!id.isBlank())
                removeOwnedNetwork(project, id.strip());
    }

    private void removeOwnedNetwork(UUID project, String name) {
        var found = run(List.of("network", "inspect", "-f", "{{.Name}} {{json .Labels}}", name), Duration.ofSeconds(20),
                null);
        found.requireSuccess();
        String[] proof = found.stdout().strip().split("\\s+", 2);
        if (proof.length != 2 || !project.toString().equals(label(proof[1], "com.codepal.project"))
                || !workspaceNamespace().equals(label(proof[1], "com.codepal.namespace")))
            throw new IllegalStateException("Project network cleanup ownership is unverified");
        String actualName = proof[0];
        var database = run(
                List.of("container", "inspect", "-f", "{{json .NetworkSettings.Networks}}", appsDatabaseContainer()),
                Duration.ofSeconds(20), null);
        if (database.success() && new ObjectMapper().readTree(database.stdout()).has(actualName))
            run(List.of("network", "disconnect", actualName, appsDatabaseContainer()), Duration.ofSeconds(20), null)
                    .requireSuccess();
        run(List.of("network", "rm", name), Duration.ofSeconds(20), null).requireSuccess();
    }

    /**
     * The internal network and the gateway on the host network, created when missing and recreated when
     * the gateway's image or configuration changed. The gateway is checked over HTTP before use.
     */
    private synchronized AppsNetwork readyGateway() {
        AppsNetwork network = appsNetwork;
        if (network != null && gatewayHealthy())
            return network;
        String name = properties.getSandbox().getAppsNetwork();
        String format = "{{range .IPAM.Config}}{{.Subnet}} {{.Gateway}}{{end}}";
        CommandResult inspected = run(List.of("network", "inspect", "-f", format, name), Duration.ofSeconds(20), null);
        if (!inspected.success()) {
            run(List.of("network", "create", "--internal", "--label", "com.codepal.apps=true", name),
                    Duration.ofSeconds(30), null).requireSuccess();
            inspected = run(List.of("network", "inspect", "-f", format, name), Duration.ofSeconds(20), null)
                    .requireSuccess();
        }
        String[] parts = inspected.stdout().trim().split("\\s+");
        if (parts.length < 2)
            throw new IllegalStateException("The apps network has no IPv4 subnet: " + inspected.stdout());
        network = new AppsNetwork(parts[0], readyAppsDatabase());
        String image = classpathImage("gateway", List.of("Dockerfile", "gateway.mjs"),
                properties.getSandbox().getGatewayImage(), GATEWAY_CONTEXT);
        String config = contentHash(List.of((image + "|" + network + "|" + ContractStore.hash(gatewaySecret()) + "|"
                + properties.getSandbox().getGatewayPort() + "|"
                + properties.getSandbox().getAppsDatabasePort()).getBytes(StandardCharsets.UTF_8)));
        CommandResult current = run(
                List.of("container", "inspect", "-f", "{{.State.Running}} {{json .Config.Labels}}", gatewayContainer()),
                Duration.ofSeconds(20), null);
        String[] state = current.stdout().trim().split("\\s+", 2);
        boolean matches = current.success() && state.length == 2 && "true".equals(state[0])
                && config.equals(label(state[1], "com.codepal.gateway.config"));
        if (!matches) {
            run(List.of("rm", "-f", gatewayContainer()), Duration.ofSeconds(30), null);
            run(List.of("run", "-d", "--name", gatewayContainer(), "--restart", "unless-stopped", "--network", "host",
                    "--label", "com.codepal.gateway.config=" + config, "--memory", "96m", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges",
                    "-e", "GATEWAY_PORT=" + properties.getSandbox().getGatewayPort(), "-e",
                    "APPS_SUBNET=" + network.subnet(), "-e", "API_RELAY_KEY=" + gatewaySecret(),
                    "-e", "MONGO_TARGET=" + network.database() + ":27017",
                    "-e", "MONGO_LISTEN=127.0.0.1:" + properties.getSandbox().getAppsDatabasePort(),
                    image), Duration.ofSeconds(60), null).requireSuccess();
            log.info("gateway_started subnet={} database={}", network.subnet(), network.database());
        }
        for (int attempt = 0; attempt < 50 && !gatewayHealthy(); attempt++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!gatewayHealthy())
            throw new IllegalStateException(
                    "The gateway did not become reachable on port " + properties.getSandbox().getGatewayPort());
        return appsNetwork = network;
    }

    /** A label's value from {@code {{json .Config.Labels}}} output, or null. */
    private static String label(String json, String key) {
        Matcher match = Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"").matcher(json);
        return match.find() ? match.group(1) : null;
    }

    private boolean gatewayHealthy() {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://" + properties.getSandbox().getGatewayHost() + ":"
                    + properties.getSandbox().getGatewayPort() + "/__gateway/health")).timeout(Duration.ofSeconds(2))
                    .GET().build();
            return GATEWAY_CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | RuntimeException unreachable) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String rootPath(String relativeRoot) {
        String root = ProjectPaths.normalize(relativeRoot, true);
        return root.equals(".") ? properties.getSandbox().getWorkspacePath()
                : properties.getSandbox().getWorkspacePath() + "/" + root;
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= properties.getSandbox().getWorkspacePath().length() ? properties.getSandbox().getWorkspacePath()
                : path.substring(0, slash);
    }

    private Duration timeout() {
        return Duration.ofMillis(Math.max(1_000, properties.getSandbox().getCommandTimeoutMs()));
    }

    private int outputLimit() {
        return properties.getSandbox().getOutputMaxChars();
    }

    private CommandResult run(List<String> args, Duration timeout, byte[] stdin) {
        return run(args, timeout, stdin, outputLimit());
    }

    private CommandResult run(List<String> args, Duration timeout, byte[] stdin, int outputLimit) {
        return runner.run(args, timeout, stdin, outputLimit);
    }

    /** Each command gets one owned process; proof retries only inspect that process. */
    private CommandResult runOwnedContainer(String name, List<String> args, Duration timeout, byte[] stdin,
            int outputLimit) {
        return new OwnedDockerCommand(runner, properties.getSandbox(), this::removeContainerQuietly)
                .execute(name, args, timeout, stdin, outputLimit);
    }
    private static boolean noSuchContainer(CommandResult result) {
        String text = (result.stdout() + "\n" + result.error()).stripLeading().toLowerCase(Locale.ROOT);
        return text.startsWith("error response from daemon: no such container")
                || text.startsWith("error: no such container");
    }

    private boolean removeContainerQuietly(String name) {
        boolean interrupted = Thread.interrupted();
        try (RunCancellation.Registration ignored = RunCancellation.bind(RunCancellation.NONE)) {
            CommandResult removed = run(List.of("rm", "-f", name), Duration.ofSeconds(10), null);
            boolean gone = removed.success() || noSuchContainer(removed);
            if (!gone)
                log.warn("owned_container_cleanup_pending container={}", name);
            return gone;
        } catch (RuntimeException failed) {
            log.warn("owned_container_cleanup_pending container={}", name);
            return false;
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private static String resourceName(String prefix, UUID projectId) {
        return (prefix == null ? "codepal-" : prefix).replaceAll("[^A-Za-z0-9_.-]", "") + projectId;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static long parseSize(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Executes one Docker CLI invocation; {@code args} exclude the docker executable itself. */
    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(List<String> args, Duration timeout, byte[] stdin, int outputLimit);
    }

    /** Docker coordinates of a project: container name and the bind-mount source as the daemon sees it. */
    public record Environment(UUID projectId, String container, String hostPath, String workspacePath) {
    }

}
