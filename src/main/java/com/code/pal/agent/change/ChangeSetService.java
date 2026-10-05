package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-scoped copy-on-write workspace. Tools mutate only the run's staging tree
 * ({@code .codepal/changesets/<runId>/workspace}); commit first checks every touched live file against
 * its baseline fingerprint, archives the project, and restores the touched files from that archive if
 * any apply operation fails. Single files go through {@link ProjectFiles}; whole-tree copies, removal
 * and restore run in the sandbox next to the files.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeSetService {
    private static final int MAX_MUTATION_BYTES = 1_000_000;
    private static final String CHANGESETS = ".codepal/changesets";
    private static final String HISTORY = ".codepal/history";
    /** Restore point recorded on the run; the archive is {@code .codepal/history/<runId>/original}. */
    public static final String SNAPSHOT_PREFIX = "archive:";
    /** Earlier name of the same archive reference. */
    private static final String LEGACY_SNAPSHOT_PREFIX = "docker:";

    private final FileChangeRepository changes;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final Map<UUID, ActiveChangeSet> active = new ConcurrentHashMap<>();

    /** Starts a change set as a copy of the project; returns the staging root for the run's tools. */
    public String start(UUID runId, UUID projectId) {
        sandbox.ensure(projectId);
        String container = CHANGESETS + "/" + runId;
        String stage = container + "/workspace";
        sandbox.removeTree(projectId, container);
        sandbox.copyTree(projectId, ".", stage);
        active.put(runId, new ActiveChangeSet(runId, projectId, stage, container));
        return stage;
    }

    public MutationResult create(AgentContext context, String relative, String content) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String path = context.resolvePath(relative);
        if (files.exists(set.projectId, set.stage, path)) throw failure("FILE_ALREADY_EXISTS", "File already exists", path);
        ensureTextSize(content);
        rememberBaseline(set, path);
        write(set, path, content);
        return record(set, path, null, FileOperation.CREATE, FileVersions.ABSENT, files.version(set.projectId, set.stage, path));
    }

    public MutationResult patch(AgentContext context, String relative, String expectedVersion, String oldText, String newText) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String path = context.resolvePath(relative);
        requireRegular(set, path);
        String actual = files.version(set.projectId, set.stage, path);
        requireVersion(path, expectedVersion, actual);
        String content = readStagedText(set, path);
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
        write(set, path, next);
        return record(set, path, null, FileOperation.PATCH, actual, files.version(set.projectId, set.stage, path));
    }

    public MutationResult delete(AgentContext context, String relative, String expectedVersion) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String path = context.resolvePath(relative);
        requireRegular(set, path);
        String actual = files.version(set.projectId, set.stage, path);
        requireVersion(path, expectedVersion, actual);
        rememberBaseline(set, path);
        try {
            files.delete(set.projectId, set.stage, path);
        } catch (RuntimeException error) {
            throw new AgentToolException("DELETE_FAILED", "Could not delete staged file", Map.of("path", path));
        }
        return record(set, path, null, FileOperation.DELETE, actual, FileVersions.ABSENT);
    }

    public MutationResult rename(AgentContext context, String sourceRelative, String targetRelative, String expectedVersion) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String source = context.resolvePath(sourceRelative);
        String target = context.resolvePath(targetRelative);
        requireRegular(set, source);
        if (files.exists(set.projectId, set.stage, target)) throw failure("RENAME_TARGET_EXISTS", "Rename target already exists", target);
        String actual = files.version(set.projectId, set.stage, source);
        requireVersion(source, expectedVersion, actual);
        rememberBaseline(set, source);
        rememberBaseline(set, target);
        try {
            files.move(set.projectId, set.stage, source, target);
        } catch (RuntimeException error) {
            throw new AgentToolException("RENAME_FAILED", "Could not rename staged file", Map.of("path", source));
        }
        return record(set, target, source, FileOperation.RENAME, actual, files.version(set.projectId, set.stage, target));
    }

    public ValidationResult validate(AgentContext context) {
        ActiveChangeSet set = requireActive(context);
        List<ValidationIssue> issues = new ArrayList<>();
        for (String path : set.touched) {
            if (!files.regularFile(set.projectId, set.stage, path)) continue;
            String text = files.readText(set.projectId, set.stage, path);
            if (text.contains("<<<<<<<") || text.contains(">>>>>>>")) {
                issues.add(new ValidationIssue(path, "MERGE_MARKER", "Unresolved merge marker"));
            }
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
        if (set.validatedRevision != set.revision) {
            throw new AgentToolException("VALIDATION_REQUIRED", "Validate the current change set before finishing");
        }
        for (Map.Entry<String, String> baseline : set.baselines.entrySet()) {
            String actual = files.version(set.projectId, ".", baseline.getKey());
            if (!actual.equals(baseline.getValue())) {
                throw new AgentToolException("STALE_PROJECT_FILE",
                        "A project file changed while the agent was working; the change set was not committed",
                        Map.of("path", baseline.getKey(), "expectedVersion", baseline.getValue(), "actualVersion", actual));
            }
        }
        String archiveRoot = HISTORY + "/" + run.getId();
        String archive = archiveRoot + "/original";
        // Archive first, on its own: restoring an incomplete archive would delete live files.
        try {
            sandbox.copyTree(set.projectId, ".", archive);
        } catch (RuntimeException error) {
            try { sandbox.removeTree(set.projectId, archiveRoot); } catch (RuntimeException ignored) { }
            throw new IllegalStateException("Could not archive the project; nothing was committed", error);
        }
        // Record the restore point before the first live write: if the process dies mid-apply,
        // AgentRunRecovery restores this archive instead of leaving a half-applied project.
        String snapshotRef = SNAPSHOT_PREFIX + run.getId();
        run.setSnapshotRef(snapshotRef);
        runs.save(run);
        try {
            for (String path : set.touched.stream().sorted().toList()) {
                if (files.regularFile(set.projectId, set.stage, path)) {
                    files.copyFile(set.projectId, set.stage, path, ".", path);
                } else {
                    files.delete(set.projectId, ".", path);
                }
            }
            sandbox.enforceQuota(set.projectId);
        } catch (RuntimeException error) {
            restore(set, archive);
            throw new IllegalStateException("Change-set commit failed; original files were restored", error);
        }
        List<String> changed = set.touched.stream().sorted().toList();
        active.remove(run.getId());
        try { sandbox.removeTree(set.projectId, set.container); } catch (RuntimeException ignored) { }
        pruneHistory(set.projectId, run.getId());
        return new CommitResult(snapshotRef, changed);
    }

    /** The archive a snapshot reference points to, or null when the reference is not an archive. */
    public static String archiveFor(String snapshotRef) {
        if (snapshotRef == null) return null;
        for (String prefix : List.of(SNAPSHOT_PREFIX, LEGACY_SNAPSHOT_PREFIX)) {
            if (snapshotRef.startsWith(prefix)) {
                try {
                    return HISTORY + "/" + UUID.fromString(snapshotRef.substring(prefix.length())) + "/original";
                } catch (IllegalArgumentException invalid) {
                    return null;
                }
            }
        }
        return null;
    }

    public void discard(UUID runId) {
        ActiveChangeSet set = active.remove(runId);
        if (set == null) return;
        try { sandbox.removeTree(set.projectId, set.container); } catch (RuntimeException ignored) { }
    }

    /**
     * Each committed build archives the whole project (rollback restores it), and .codepal is outside
     * the quota. Only the latest build can be rolled back, so older archives are removed.
     */
    private void pruneHistory(UUID projectId, UUID keepRunId) {
        try {
            for (ProjectFiles.Entry entry : files.list(projectId, HISTORY, 1)) {
                if (entry.directory() && !entry.path().equals(keepRunId.toString())) {
                    sandbox.removeTree(projectId, HISTORY + "/" + entry.path());
                }
            }
        } catch (RuntimeException error) {
            log.warn("sandbox_history_prune_failed projectId={} reason={}", projectId, error.getMessage());
        }
    }

    /**
     * Removes the staging area of a run whose process died: its in-memory change set is gone, so
     * {@link #discard} can no longer find it. Called by crash recovery after any restore.
     */
    public void cleanupAbandoned(UUID projectId, UUID runId) {
        if (active.containsKey(runId)) return;    // still owned by a live run in this process
        try {
            sandbox.removeTree(projectId, CHANGESETS + "/" + runId);
        } catch (RuntimeException error) {
            log.warn("abandoned_changeset_cleanup_failed runId={} reason={}", runId, error.getMessage());
        }
    }

    private void restore(ActiveChangeSet set, String archive) {
        for (String path : set.touched) {
            try {
                if (files.regularFile(set.projectId, archive, path)) {
                    files.copyFile(set.projectId, archive, path, ".", path);
                } else {
                    files.delete(set.projectId, ".", path);
                }
            } catch (RuntimeException error) {
                log.error("changeset_restore_failed runId={} path={} reason={}", set.runId, path, error.getMessage());
            }
        }
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
        return new WorkspaceView(".", set.stage, set.container, set.revision, List.copyOf(set.touched));
    }

    /** Read-only view of the currently staged contents; never exposes the live workspace for editing. */
    public List<StagedFile> stagedFiles(UUID runId) {
        ActiveChangeSet set = active.get(runId);
        if (set == null) return List.of();
        return set.touched.stream().sorted().map(path -> {
            try {
                if (!files.regularFile(set.projectId, set.stage, path)) return new StagedFile(path, null, true);
                byte[] bytes = files.readHead(set.projectId, set.stage, path, 200_001);
                if (bytes.length > 200_000 || isBinary(bytes)) return new StagedFile(path, null, false);
                return new StagedFile(path, new String(bytes, StandardCharsets.UTF_8), false);
            } catch (RuntimeException error) {
                return new StagedFile(path, null, false);
            }
        }).toList();
    }

    /** Fresh copy of the live project for a validation operation, so scripts never write into it. */
    public String createValidationBaseline(AgentContext context, String name) {
        ActiveChangeSet set = requireActive(context);
        String baseline = set.container + "/baseline-" + safeName(name);
        sandbox.copyTree(set.projectId, ".", baseline);
        return baseline;
    }

    /** Disposable copy of the current staged revision used for commands that may emit build output. */
    public String createValidationCandidate(AgentContext context, String name) {
        ActiveChangeSet set = requireActive(context);
        String candidate = set.container + "/candidate-" + safeName(name);
        sandbox.copyTree(set.projectId, set.stage, candidate);
        return candidate;
    }

    private static String safeName(String name) {
        return name == null ? "validation" : name.replaceAll("[^A-Za-z0-9_-]", "_");
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
    private void rememberBaseline(ActiveChangeSet set, String path) {
        set.baselines.computeIfAbsent(path, key -> files.version(set.projectId, ".", key));
    }
    private void requireRegular(ActiveChangeSet set, String path) {
        if (!files.regularFile(set.projectId, set.stage, path)) {
            throw new AgentToolException("FILE_NOT_FOUND", "File does not exist", Map.of("path", path));
        }
    }
    private String readStagedText(ActiveChangeSet set, String path) {
        byte[] bytes;
        try {
            bytes = files.readBytes(set.projectId, set.stage, path, MAX_MUTATION_BYTES);
        } catch (RuntimeException error) {
            throw new AgentToolException("READ_FAILED", "Could not read staged file", Map.of("path", path));
        }
        for (byte value : bytes) if (value == 0) throw new AgentToolException("BINARY_FILE", "Binary files cannot be modified", Map.of("path", path));
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private void write(ActiveChangeSet set, String path, String content) {
        try {
            files.writeText(set.projectId, set.stage, path, content);
        } catch (RuntimeException error) {
            throw new AgentToolException("WRITE_FAILED", "Could not write staged file", Map.of("path", path));
        }
    }
    private static void requireVersion(String path, String expected, String actual) {
        if (expected == null || expected.isBlank()) throw new AgentToolException("EXPECTED_VERSION_REQUIRED", "An exact file version from readFile is required", Map.of("path", path, "actualVersion", actual));
        if (!expected.equals(actual)) throw new AgentToolException("STALE_FILE_VERSION", "File version no longer matches", Map.of("path", path, "expectedVersion", expected, "actualVersion", actual, "action", "Re-read the file before retrying"));
    }
    private static AgentToolException failure(String code, String message, String path) { return new AgentToolException(code, message, Map.of("path", path)); }
    private static void ensureTextSize(String text) { if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MUTATION_BYTES) throw new AgentToolException("FILE_TOO_LARGE", "Agent file mutations are limited to 1 MB"); }

    private static final class ActiveChangeSet {
        final UUID runId; final UUID projectId; final String stage; final String container;
        final Map<String, String> baselines = new LinkedHashMap<>(); final Set<String> touched = new LinkedHashSet<>();
        int revision; int validatedRevision = -1; int repairAttempts; boolean repairPending;
        List<ProjectDiagnostic> lastDiagnostics = List.of();
        ActiveChangeSet(UUID runId, UUID projectId, String stage, String container) {
            this.runId = runId; this.projectId = projectId; this.stage = stage; this.container = container;
        }
    }

    public record MutationResult(String path, String sourcePath, FileOperation operation, String beforeVersion, String afterVersion, int changeSetRevision) {}
    public record ValidationIssue(String path, String code, String message) {}
    public record ValidationResult(boolean valid, int changeSetRevision, List<ValidationIssue> issues, String summary) {}
    public record CommitResult(String snapshotRef, List<String> changedPaths) {}
    /** Project-relative roots: {@code liveRoot} is always {@code "."}. */
    public record WorkspaceView(String liveRoot, String stagedRoot, String container, int revision, List<String> touchedPaths) {}
    public record StagedFile(String path, String content, boolean deleted) {}
    public record ValidationProgress(int revision, int validatedRevision, int repairAttempts,
                                     int repairAttemptsRemaining, boolean repairLimitExhausted,
                                     List<ProjectDiagnostic> diagnostics) {}

    private static boolean isBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++) if (bytes[i] == 0) return true;
        return false;
    }
}
