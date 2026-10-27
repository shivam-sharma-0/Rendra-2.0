package com.code.pal.agent.stack;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs a project's dependencies once per set of dependency manifests and links them in.
 *
 * <p>Each installed set lives in {@code .codepal/deps/<hash>/}, keyed by the stack, the install command
 * and the manifests' content; a root that needs it gets {@code node_modules} (or the stack's equivalent)
 * as a relative link to that set. So tree copies (staging, validation copies, history) never copy
 * dependencies, a change that leaves the manifests alone reuses the installed set, and a rollback only
 * needs the link re-created. The install itself runs in the sandbox's networked install step with
 * lifecycle scripts disabled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyService {
    static final String STORE = ProjectPaths.INTERNAL + "/deps";
    private static final String COMPLETE = ".complete";
    /** Installed sets kept per project (the most recently used ones). */
    private static final int MAX_SETS = 5;
    private static final int ERROR_TAIL_CHARS = 1_500;
    /** Registry connectivity, not a dependency defect: the bounded install budget retries it instead of a code repair. */
    private static final Pattern TRANSIENT_NETWORK = Pattern.compile("(?i)\\bcode (?:ETIMEDOUT|ECONNRESET|ECONNREFUSED"
            + "|EAI_AGAIN|ENOTFOUND|ESOCKETTIMEDOUT|EPIPE|E429|E50[0234])\\b|problem related to network connectivity");

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final StackPacks stacks;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final AgentProperties properties;

    /** Outcome of {@link #prepare}: {@code error} is set when the dependencies could not be installed. */
    public record Preparation(boolean needed, boolean installed, String error) {
        public boolean failed() {
            return error != null;
        }
    }

    private record InstallFailure(String message, boolean retryable) {}

    /**
     * Makes the dependencies declared under {@code root} available there, installing them if this exact
     * set has not been installed for the project yet. Never throws for an install failure: the error is
     * returned so validation can report it to the model.
     */
    public Preparation prepare(UUID projectId, String root) {
        // One at a time per project: a draft build and a validation may need the same set at once, and
        // two installs into one folder (or a prune during an install) would break both.
        ReentrantLock lock = locks.computeIfAbsent(projectId, id -> new ReentrantLock());
        lock.lock();
        try {
            return prepareLocked(projectId, root);
        } finally {
            lock.unlock();
        }
    }

    /** Resolve npm locks in disposable dependency sets; never writes the calling workspace. */
    public Map<String, String> refreshNpmLocks(UUID projectId, String root) {
        var lock = locks.computeIfAbsent(projectId, id -> new ReentrantLock());
        lock.lock();
        try {
            var packages = stacks.detect(files, projectId, root).packageRoots(files, projectId, root);
            if (packages.isEmpty() || packages.size() > 3) throw new IllegalArgumentException("Unsupported package layout");
            var commands = new LinkedHashMap<String, List<String>>();
            for (String pkg : packages) {
                String packageRoot = pkg.equals(".") ? root : ProjectPaths.combine(root, pkg);
                var stack = stacks.detect(files, projectId, packageRoot);
                var command = new ArrayList<>(stack.installCommand(files, projectId, packageRoot));
                if (!(stack instanceof NodePack) || command.size() < 2 || !command.getFirst().equals("npm"))
                    throw new IllegalArgumentException("Lock refresh supports npm Node/MERN packages only");
                command.set(1, "install"); // Fixed backend command, scripts disabled; preserves compatible locked versions.
                commands.put(pkg, List.copyOf(command));
            }
            var resolved = new LinkedHashMap<String, String>();
            for (var entry : commands.entrySet()) {
                String packageRoot = entry.getKey().equals(".") ? root : ProjectPaths.combine(root, entry.getKey());
                var stack = stacks.detect(files, projectId, packageRoot);
                String set = STORE + "/" + key(stack, entry.getValue(), projectId, packageRoot);
                if (!files.regularFile(projectId, set, COMPLETE)) {
                    String error = install(stack, entry.getValue(), projectId, packageRoot, set);
                    if (error != null) throw new IllegalStateException(error);
                }
                String content = new String(files.readBytes(projectId, set, "package-lock.json", 1_000_000), StandardCharsets.UTF_8);
                var mapper = new ObjectMapper();
                var generated = mapper.readTree(content).path("packages").path("");
                var manifest = mapper.readTree(new String(files.readBytes(projectId, packageRoot, "package.json", 1_000_000), StandardCharsets.UTF_8));
                if (generated.isMissingNode()) throw new IllegalStateException("Resolved npm lock lacks its package root");
                if (!NpmLockfiles.matches(manifest, generated)) throw new IllegalStateException("Resolved npm lock does not match the package manifest");
                resolved.put(entry.getKey().equals(".") ? "package-lock.json" : entry.getKey() + "/package-lock.json", content);
            }
            return Collections.unmodifiableMap(resolved);
        } finally { lock.unlock(); }
    }

    private Preparation prepareLocked(UUID projectId, String root) {
        StackPack project = stacks.detect(files, projectId, root);
        List<String> packages = project.packageRoots(files, projectId, root);
        if (packages.equals(List.of("."))) return preparePackage(projectId, root);
        // Several packages (a MERN client and server): each installs and links its own dependencies.
        boolean needed = false;
        boolean installed = false;
        for (String pkg : packages) {
            Preparation part = preparePackage(projectId, ProjectPaths.combine(root, pkg));
            if (part.failed()) return new Preparation(true, false, pkg + "/: " + part.error());
            needed |= part.needed();
            installed |= part.installed();
        }
        return new Preparation(needed, installed, null);
    }

    private Preparation preparePackage(UUID projectId, String root) {
        StackPack stack = stacks.detect(files, projectId, root);
        List<String> command = stack.installCommand(files, projectId, root);
        if (command.isEmpty() || stack.dependencyDirectory() == null) return new Preparation(false, false, null);
        String key = key(stack, command, projectId, root);
        String set = STORE + "/" + key;
        boolean installed = false;
        if (!files.regularFile(projectId, set, COMPLETE)) {
            String error = install(stack, command, projectId, root, set);
            if (error != null) return new Preparation(true, false, error);
            installed = true;
        }
        if (!installed) files.writeText(projectId, set, COMPLETE, Long.toString(System.currentTimeMillis()));   // last use, for pruning
        sandbox.link(projectId, ProjectPaths.combine(root, stack.dependencyDirectory()), set + "/" + stack.dependencyDirectory());
        prune(projectId, key);
        return new Preparation(true, installed, null);
    }

    private String install(StackPack stack, List<String> command, UUID projectId, String root, String set) {
        int attempts = Math.max(1, Math.min(3, properties.getSandbox().getDependencyInstallAttempts()));
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (Thread.currentThread().isInterrupted() || RunCancellation.current().isCancelled()) throw new LlmCancelledException();
            var failure = installAttempt(stack, retryCommand(command, attempt), projectId, root, set);
            if (failure == null) return null;
            if (!failure.retryable() || attempt + 1 == attempts) return failure.message();
            log.warn("dependency_runtime_retry projectId={} stack={} attempt={}", projectId, stack.id(), attempt + 1);
        }
        throw new IllegalStateException("Unreachable dependency install loop");
    }

    private static List<String> retryCommand(List<String> command, int attempt) {
        if (attempt == 0 || command.isEmpty() || !command.getFirst().equals("npm")) return command;
        var retry = new ArrayList<>(command);
        retry.remove("--prefer-offline");
        retry.add("--prefer-online");
        retry.add("--include=optional");
        return List.copyOf(retry);
    }

    private InstallFailure installAttempt(StackPack stack, List<String> command, UUID projectId, String root, String set) {
        sandbox.removeTree(projectId, set);
        for (String manifest : stack.dependencyManifests()) {
            if (files.regularFile(projectId, root, manifest)) files.copyFile(projectId, root, manifest, set, manifest);
        }
        long started = System.nanoTime();
        SandboxPort.CommandResult result = sandbox.install(projectId, set, command);
        if (result.cancelled()) throw new LlmCancelledException();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (!result.success()) {
            log.warn("dependency_install_failed projectId={} stack={} exitCode={} elapsedMs={}", projectId, stack.id(),
                    result.exitCode(), elapsedMs);
            removeQuietly(projectId, set);
            String output = result.stdout() + "\n" + result.error();
            String reason = result.timedOut() ? "the install timed out" : tail(output);
            return new InstallFailure("Dependencies could not be installed: " + reason,
                    !result.timedOut() && TRANSIENT_NETWORK.matcher(output).find());
        }
        String tooLarge = sizeError(projectId, set, stack.dependencyDirectory());
        if (tooLarge != null) {
            removeQuietly(projectId, set);
            return new InstallFailure(tooLarge, false);
        }
        var check = stack.dependencyCheck(files, projectId, set);
        if (!check.isEmpty()) {
            var verified = sandbox.exec(projectId, set, check);
            if (verified.cancelled()) throw new LlmCancelledException();
            if (!verified.success()) {
                removeQuietly(projectId, set);
                return new InstallFailure("Dependency runtime check failed: " + (verified.timedOut() ? "loader timed out"
                        : tail(verified.stdout() + "\n" + verified.error())), verified.started() && !verified.timedOut());
            }
        }
        files.writeText(projectId, set, COMPLETE, Long.toString(System.currentTimeMillis()));
        log.info("dependency_install_done projectId={} stack={} elapsedMs={}", projectId, stack.id(), elapsedMs);
        return null;
    }

    /** Dependencies are outside the project quota, so they get their own bound. */
    private String sizeError(UUID projectId, String set, String directory) {
        long limit = properties.getSandbox().getMaxDependencyBytes();
        if (limit <= 0) return null;
        SandboxPort.CommandResult du = sandbox.exec(projectId, set, List.of("du", "-sk", directory));
        if (!du.success()) return null;   // no du (local development on Windows): nothing to enforce
        try {
            long bytes = Long.parseLong(du.stdout().trim().split("\\s+", 2)[0]) * 1024L;
            return bytes > limit ? "Dependencies are too large: " + bytes / 1_000_000 + " MB > " + limit / 1_000_000 + " MB" : null;
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /** Keeps the {@link #MAX_SETS} most recently used sets, always including {@code keep}. */
    private void prune(UUID projectId, String keep) {
        try {
            List<String> sets = new ArrayList<>();
            for (ProjectFiles.Entry entry : files.list(projectId, STORE, 1)) {
                if (entry.directory() && !entry.path().equals(keep)) sets.add(entry.path());
            }
            if (sets.size() < MAX_SETS) return;
            sets.sort(Comparator.comparingLong((String name) -> lastUsed(projectId, name)).reversed());
            for (String stale : sets.subList(MAX_SETS - 1, sets.size())) removeQuietly(projectId, STORE + "/" + stale);
        } catch (RuntimeException error) {
            log.warn("dependency_prune_failed projectId={} reason={}", projectId, error.getMessage());
        }
    }

    private long lastUsed(UUID projectId, String name) {
        try {
            return Long.parseLong(files.readText(projectId, STORE + "/" + name, COMPLETE).trim());
        } catch (RuntimeException incomplete) {
            return 0;   // an interrupted install: oldest
        }
    }

    private String key(StackPack stack, List<String> command, UUID projectId, String root) {
        String material = stack.id() + "\n" + properties.getSandbox().getImage() + "\n" + String.join(" ", command)
                + "\n" + stack.dependencyCheck(files, projectId, root) + "\n" + stack.dependencyFingerprint(files, projectId, root);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void removeQuietly(UUID projectId, String path) {
        try {
            sandbox.removeTree(projectId, path);
        } catch (RuntimeException error) {
            log.warn("dependency_remove_failed projectId={} path={} reason={}", projectId, path, error.getMessage());
        }
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= ERROR_TAIL_CHARS ? trimmed : "..." + trimmed.substring(trimmed.length() - ERROR_TAIL_CHARS);
    }
}
