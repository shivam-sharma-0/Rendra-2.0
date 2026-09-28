package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.snapshot.WorkspaceSnapshotService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.validation.ProjectDiagnostic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-scoped copy-on-write workspace. Tools mutate only the staging tree; commit first checks every
 * touched live file against its baseline fingerprint, snapshots the original project, and rolls
 * back from a private archive if any apply operation fails.
 */
@Service
@RequiredArgsConstructor
public class ChangeSetService {
    private static final Set<String> COPY_IGNORED = Set.of(".git", ".secrets", ".agent-changesets", ".agent-history",
            "node_modules", "build", "dist", "coverage");
    private static final int MAX_MUTATION_BYTES = 1_000_000;

    private final FileChangeRepository changes;
    private final WorkspaceSnapshotService snapshots;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final Map<UUID, ActiveChangeSet> active = new ConcurrentHashMap<>();

    public Path start(UUID runId, UUID projectId, Path liveRoot) {
        Path live = liveRoot.toAbsolutePath().normalize();
        Path container = live.getParent().resolve(".agent-changesets").resolve(runId.toString()).normalize();
        if (!container.startsWith(live.getParent())) throw new IllegalStateException("Invalid change-set location");
        Path stage = container.resolve("workspace");
        try {
            deleteTree(container);
            Files.createDirectories(stage);
            copyTree(live, stage);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create isolated agent workspace", e);
        }
        active.put(runId, new ActiveChangeSet(runId, projectId, live, stage, container));
        return stage;
    }

    public MutationResult create(AgentContext context, String relative, String content) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        Path target = context.resolveSafe(relative);
        String path = relative(context.workspace(), target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure("FILE_ALREADY_EXISTS", "File already exists", path, target);
        ensureTextSize(content);
        rememberBaseline(set, path);
        atomicWrite(target, content);
        return record(set, path, null, FileOperation.CREATE, FileVersions.ABSENT, FileVersions.of(target));
    }

    public MutationResult patch(AgentContext context, String relative, String expectedVersion, String oldText, String newText) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        Path target = context.resolveSafe(relative);
        String path = relative(context.workspace(), target);
        requireRegular(target, path);
        String actual = FileVersions.of(target);
        requireVersion(path, expectedVersion, actual);
        String content = readText(target, path);
        int first = content.indexOf(oldText);
        if (first < 0) throw new AgentToolException("PATCH_TARGET_NOT_FOUND", "Expected patch target was not found",
                Map.of("path", path, "actualVersion", actual, "action", "Re-read the file and construct a new patch"));
        if (content.indexOf(oldText, first + Math.max(1, oldText.length())) >= 0) {
            throw new AgentToolException("PATCH_TARGET_AMBIGUOUS", "Expected patch target occurs more than once",
                    Map.of("path", path, "actualVersion", actual, "action", "Use a larger unique oldText block"));
        }
        String next = content.substring(0, first) + newText + content.substring(first + oldText.length());
        ensureTextSize(next);
        rememberBaseline(set, path);
        atomicWrite(target, next);
        return record(set, path, null, FileOperation.PATCH, actual, FileVersions.of(target));
    }

    public MutationResult delete(AgentContext context, String relative, String expectedVersion) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        Path target = context.resolveSafe(relative);
        String path = relative(context.workspace(), target);
        requireRegular(target, path);
        String actual = FileVersions.of(target);
        requireVersion(path, expectedVersion, actual);
        rememberBaseline(set, path);
        try { Files.delete(target); } catch (IOException e) { throw new AgentToolException("DELETE_FAILED", "Could not delete staged file"); }
        return record(set, path, null, FileOperation.DELETE, actual, FileVersions.ABSENT);
    }

    public MutationResult rename(AgentContext context, String sourceRelative, String targetRelative, String expectedVersion) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        Path source = context.resolveSafe(sourceRelative);
        Path target = context.resolveSafe(targetRelative);
        String sourcePath = relative(context.workspace(), source);
        String targetPath = relative(context.workspace(), target);
        requireRegular(source, sourcePath);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw failure("RENAME_TARGET_EXISTS", "Rename target already exists", targetPath, target);
        String actual = FileVersions.of(source);
        requireVersion(sourcePath, expectedVersion, actual);
        rememberBaseline(set, sourcePath);
        rememberBaseline(set, targetPath);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.move(source, target);
        } catch (IOException e) { throw new AgentToolException("RENAME_FAILED", "Could not rename staged file"); }
        return record(set, targetPath, sourcePath, FileOperation.RENAME, actual, FileVersions.of(target));
    }

    public ValidationResult validate(AgentContext context) {
        ActiveChangeSet set = requireActive(context);
        List<ValidationIssue> issues = new ArrayList<>();
        for (String path : set.touched) {
            Path file = set.stage.resolve(path).normalize();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            String text = readText(file, path);
            if (text.contains("<<<<<<<") || text.contains(">>>>>>>")) issues.add(new ValidationIssue(path, "MERGE_MARKER", "Unresolved merge marker"));
            if (path.toLowerCase(Locale.ROOT).endsWith(".json")) {
                try { objectMapper.readTree(text); }
                catch (RuntimeException error) { issues.add(new ValidationIssue(path, "INVALID_JSON", "JSON is not syntactically valid")); }
            }
        }
        boolean valid = issues.isEmpty() && !set.touched.isEmpty();
        return new ValidationResult(valid, set.revision, List.copyOf(issues),
                valid ? "Deterministic file validation passed" : "Fix validation issues and validate again");
    }

    public CommitResult commit(AgentContext context, AgentRun run) {
        ActiveChangeSet set = requireActive(context);
        if (set.touched.isEmpty()) { discard(run.getId()); return new CommitResult(null, List.of()); }
        if (set.validatedRevision != set.revision) throw new AgentToolException("VALIDATION_REQUIRED", "Validate the current change set before finishing");
        for (Map.Entry<String, String> baseline : set.baselines.entrySet()) {
            String actual = FileVersions.of(set.live.resolve(baseline.getKey()));
            if (!actual.equals(baseline.getValue())) throw new AgentToolException("STALE_PROJECT_FILE",
                    "A project file changed while the agent was working; the change set was not committed",
                    Map.of("path", baseline.getKey(), "expectedVersion", baseline.getValue(), "actualVersion", actual));
        }
        String snapshotRef = snapshots.createSnapshot(set.live, run.getId());
        run.setSnapshotRef(snapshotRef);
        runs.save(run);
        Path archive = set.live.getParent().resolve(".agent-history").resolve(run.getId().toString()).resolve("original").normalize();
        try {
            Files.createDirectories(archive);
            archiveOriginals(set, archive);
            apply(set);
        } catch (Exception error) {
            restoreArchive(set, archive);
            throw new IllegalStateException("Change-set commit failed; original files were restored", error);
        }
        List<String> changed = set.touched.stream().sorted().toList();
        active.remove(run.getId());
        try { deleteTree(set.container); } catch (IOException ignored) { }
        return new CommitResult(snapshotRef, changed);
    }

    public void discard(UUID runId) {
        ActiveChangeSet set = active.remove(runId);
        if (set == null) return;
        try { deleteTree(set.container); } catch (IOException ignored) { }
    }

    private MutationResult record(ActiveChangeSet set, String path, String source, FileOperation operation, String before, String after) {
        if (set.repairPending) {
            set.repairAttempts++;
            set.repairPending = false;
        }
        set.touched.add(path);
        if (source != null) set.touched.add(source);
        set.revision++;
        set.validatedRevision = -1;
        changes.save(FileChange.builder().runId(set.runId).projectId(set.projectId).path(path).sourcePath(source)
                .operation(operation).beforeVersion(before).afterVersion(after).build());
        return new MutationResult(path, source, operation, before, after, set.revision);
    }

    public WorkspaceView view(AgentContext context) {
        ActiveChangeSet set = requireActive(context);
        return new WorkspaceView(set.live, set.stage, set.container, set.revision, List.copyOf(set.touched));
    }

    /** Read-only view of the currently staged contents; never exposes the live workspace for editing. */
    public List<StagedFile> stagedFiles(UUID runId) {
        ActiveChangeSet set = active.get(runId);
        if (set == null) return List.of();
        return set.touched.stream().sorted().map(path -> {
            Path file = set.stage.resolve(path).normalize();
            if (!file.startsWith(set.stage) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return new StagedFile(path, null, true);
            }
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > 200_000 || isBinary(bytes)) return new StagedFile(path, null, false);
                return new StagedFile(path, new String(bytes, StandardCharsets.UTF_8), false);
            } catch (IOException e) {
                return new StagedFile(path, null, false);
            }
        }).toList();
    }

    /** Fresh baseline copy for a validation operation, so scripts never write generated output to the live project. */
    public Path createValidationBaseline(AgentContext context, String name) {
        ActiveChangeSet set = requireActive(context);
        String safeName = name == null ? "validation" : name.replaceAll("[^A-Za-z0-9_-]", "_");
        Path baseline = set.container.resolve("baseline-" + safeName).normalize();
        if (!baseline.startsWith(set.container)) throw new IllegalStateException("Invalid validation workspace");
        try {
            deleteTree(baseline);
            Files.createDirectories(baseline);
            copyTree(set.live, baseline);
            return baseline;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare baseline validation workspace", e);
        }
    }

    /** Disposable copy of the current staged revision used for commands that may emit build output. */
    public Path createValidationCandidate(AgentContext context, String name) {
        ActiveChangeSet set = requireActive(context);
        String safeName = name == null ? "validation" : name.replaceAll("[^A-Za-z0-9_-]", "_");
        Path candidate = set.container.resolve("candidate-" + safeName).normalize();
        if (!candidate.startsWith(set.container)) throw new IllegalStateException("Invalid validation workspace");
        try {
            deleteTree(candidate);
            Files.createDirectories(candidate);
            copyTree(set.stage, candidate);
            return candidate;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare staged validation workspace", e);
        }
    }

    public ValidationProgress recordProjectValidation(AgentContext context, boolean passed,
                                                       List<ProjectDiagnostic> diagnostics) {
        return recordProjectValidation(context, passed, diagnostics, true);
    }

    public ValidationProgress recordProjectValidation(AgentContext context, boolean passed,
                                                       List<ProjectDiagnostic> diagnostics,
                                                       boolean certifyChangeSet) {
        ActiveChangeSet set = requireActive(context);
        set.lastDiagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (passed) {
            if (certifyChangeSet) {
                set.validatedRevision = set.revision;
                set.repairPending = false;
            }
        } else {
            set.validatedRevision = -1;
            set.repairPending = true;
        }
        return progress(set);
    }

    public ValidationProgress validationProgress(AgentContext context) { return progress(requireActive(context)); }
    public ValidationProgress recordTerminalValidationFailure(AgentContext context, List<ProjectDiagnostic> diagnostics) {
        ActiveChangeSet set = requireActive(context);
        set.lastDiagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        set.validatedRevision = -1;
        set.repairPending = true;
        set.repairAttempts = Math.max(0, properties.getMaxRepairAttempts());
        return progress(set);
    }
    public boolean hasUnvalidatedChanges(AgentContext context) {
        ActiveChangeSet set = requireActive(context);
        return !set.touched.isEmpty() && set.validatedRevision != set.revision;
    }

    private ValidationProgress progress(ActiveChangeSet set) {
        int max = Math.max(0, properties.getMaxRepairAttempts());
        return new ValidationProgress(set.revision, set.validatedRevision, set.repairAttempts,
                Math.max(0, max - set.repairAttempts), set.repairPending && set.repairAttempts >= max,
                set.lastDiagnostics);
    }

    private void requireRepairAvailable(ActiveChangeSet set) {
        int max = Math.max(0, properties.getMaxRepairAttempts());
        if (set.repairPending && set.repairAttempts >= max) {
            throw new AgentToolException("REPAIR_LIMIT_EXCEEDED", "Validation still fails and the repair limit is exhausted",
                    Map.of("repairAttempts", set.repairAttempts, "maxRepairAttempts", max,
                            "diagnostics", set.lastDiagnostics));
        }
    }

    private static void requirePlan(AgentContext context) {
        if (!context.plan().exists()) throw new AgentToolException("PLANNING_REQUIRED", "Create a concrete plan before modifying files");
    }
    private ActiveChangeSet requireActive(AgentContext context) {
        ActiveChangeSet set = active.get(context.runId());
        if (set == null || !set.projectId.equals(context.projectId())) throw new AgentToolException("CHANGE_SET_NOT_ACTIVE", "No active change set for this run");
        return set;
    }
    private static void rememberBaseline(ActiveChangeSet set, String path) { set.baselines.computeIfAbsent(path, key -> FileVersions.of(set.live.resolve(key))); }
    private static void requireVersion(String path, String expected, String actual) {
        if (expected == null || expected.isBlank()) throw new AgentToolException("EXPECTED_VERSION_REQUIRED", "An exact file version from readFile is required", Map.of("path", path, "actualVersion", actual));
        if (!expected.equals(actual)) throw new AgentToolException("STALE_FILE_VERSION", "File version no longer matches", Map.of("path", path, "expectedVersion", expected, "actualVersion", actual, "action", "Re-read the file before retrying"));
    }
    private static AgentToolException failure(String code, String message, String path, Path ignored) { return new AgentToolException(code, message, Map.of("path", path)); }
    private static void requireRegular(Path file, String path) { if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new AgentToolException("FILE_NOT_FOUND", "File does not exist", Map.of("path", path)); }
    private static String readText(Path file, String path) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (byte value : bytes) if (value == 0) throw new AgentToolException("BINARY_FILE", "Binary files cannot be modified", Map.of("path", path));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new AgentToolException("READ_FAILED", "Could not read staged file", Map.of("path", path)); }
    }
    private static void ensureTextSize(String text) { if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MUTATION_BYTES) throw new AgentToolException("FILE_TOO_LARGE", "Agent file mutations are limited to 1 MB"); }
    private static void atomicWrite(Path target, String content) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".agent-", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new AgentToolException("WRITE_FAILED", "Could not write staged file"); }
    }
    private static String relative(Path root, Path file) { return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'); }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(directory);
                if (relative.getNameCount() > 0 && COPY_IGNORED.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    Path destination = target.resolve(source.relativize(file));
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static void archiveOriginals(ActiveChangeSet set, Path archive) throws IOException {
        for (String path : set.touched) {
            Path source = set.live.resolve(path);
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                Path target = archive.resolve(path); Files.createDirectories(target.getParent()); Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static void apply(ActiveChangeSet set) throws IOException {
        for (String path : set.touched.stream().sorted().toList()) {
            Path staged = set.stage.resolve(path); Path live = set.live.resolve(path);
            if (Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(live.getParent());
                Path temp = Files.createTempFile(live.getParent(), ".agent-commit-", ".tmp");
                Files.copy(staged, temp, StandardCopyOption.REPLACE_EXISTING);
                try { Files.move(temp, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, live, StandardCopyOption.REPLACE_EXISTING); }
            } else Files.deleteIfExists(live);
        }
    }
    private static void restoreArchive(ActiveChangeSet set, Path archive) {
        for (String path : set.touched) {
            try {
                Path original = archive.resolve(path); Path live = set.live.resolve(path);
                if (Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectories(live.getParent()); Files.copy(original, live, StandardCopyOption.REPLACE_EXISTING); }
                else Files.deleteIfExists(live);
            } catch (IOException ignored) { }
        }
    }
    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class ActiveChangeSet {
        final UUID runId; final UUID projectId; final Path live; final Path stage; final Path container;
        final Map<String, String> baselines = new LinkedHashMap<>(); final Set<String> touched = new LinkedHashSet<>();
        int revision; int validatedRevision = -1; int repairAttempts; boolean repairPending;
        List<ProjectDiagnostic> lastDiagnostics = List.of();
        ActiveChangeSet(UUID runId, UUID projectId, Path live, Path stage, Path container) { this.runId=runId; this.projectId=projectId; this.live=live; this.stage=stage; this.container=container; }
    }

    public record MutationResult(String path, String sourcePath, FileOperation operation, String beforeVersion, String afterVersion, int changeSetRevision) {}
    public record ValidationIssue(String path, String code, String message) {}
    public record ValidationResult(boolean valid, int changeSetRevision, List<ValidationIssue> issues, String summary) {}
    public record CommitResult(String snapshotRef, List<String> changedPaths) {}
    public record WorkspaceView(Path liveRoot, Path stagedRoot, Path container, int revision, List<String> touchedPaths) {}
    public record StagedFile(String path, String content, boolean deleted) {}
    public record ValidationProgress(int revision, int validatedRevision, int repairAttempts,
                                     int repairAttemptsRemaining, boolean repairLimitExhausted,
                                     List<ProjectDiagnostic> diagnostics) {}

    private static boolean isBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++) if (bytes[i] == 0) return true;
        return false;
    }
}
