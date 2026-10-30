package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.code.pal.agent.contract.UnitWriteScope;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.stack.NodePack;
import com.code.pal.agent.stack.NpmLockfiles;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.brief.ProjectBriefFile;
import com.code.pal.project.domain.ProjectType;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Run-scoped copy-on-write workspace. Tools mutate only the run's staging tree
 * ({@code .codepal/changesets/<runId>/epoch-<generation>/workspace}); commit first checks every touched live file against
 * its baseline fingerprint, journals a proved archive, and verifies touched-file compensation after failure.
 * Unknown finalization/restore outcomes retain COMMITTING and quarantine rather than claiming restoration. Single files go through {@link ProjectFiles}; whole-tree copies, removal
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

    private static final long MAX_COPY_BYTES = 10_000_000;
    private final FileChangeRepository changes;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final ProjectService projects;
    private ProjectLeaseService leases;

    @Autowired
    void useLeases(ProjectLeaseService leases) {
        this.leases = leases;
    }

    private PromotionJournal journal;

    private SavedBuildStore savedBuilds;

    @Autowired(required = false)
    void useSavedBuilds(SavedBuildStore savedBuilds) {
        this.savedBuilds = savedBuilds;
    }

    @Autowired
    void useJournal(PromotionJournal journal) {
        this.journal = journal;
    }

    private SequentialCheckpoints checkpoints;

    @Autowired
    void useCheckpoints(SequentialCheckpoints checkpoints) {
        this.checkpoints = checkpoints;
    }

    private ApprovedProjectMemory memory;

    @Autowired
    public void useApprovedMemory(ApprovedProjectMemory memory) {
        this.memory = Objects.requireNonNull(memory);
    }

    private final Map<UUID, ActiveChangeSet> active = new ConcurrentHashMap<>();
    private FailedDraftService failedDrafts;

    @Autowired(required = false)
    void useFailedDrafts(FailedDraftService service) {
        this.failedDrafts = service;
    }

    /** Starts a change set as a copy of the project; returns the staging root for the run's tools. */
    public String start(UUID runId, UUID projectId) {
        return leases == null ? startOwned(runId, projectId)
                : leases.guarded(projectId, () -> startOwned(runId, projectId));
    }

    private String startOwned(UUID runId, UUID projectId) {
        sandbox.ensure(projectId);
        var fence = RunFence.current();
        String container = CHANGESETS + "/" + runId + (fence == null ? "" : "/epoch-" + fence.epoch());
        String stage = container + "/workspace";
        sandbox.removeTree(projectId, container);
        sandbox.copyTree(projectId, ".", stage);
        var set = new ActiveChangeSet(runId, projectId, stage, container);
        // Bind failed-output reuse to the accepted starting code/approval, before any staged mutation.
        if (failedDrafts != null) {
            try {
                set.failedDraftBasis = failedDrafts.basis(projectId, stage);
                failedDrafts.persistBasis(runs.findById(runId).orElseThrow(), container, set.failedDraftBasis);
            } catch (RuntimeException unavailable) {
                set.failedDraftBasis = null;
                log.warn("failed_draft_basis_unavailable runId={} errorType={}", runId,
                        unavailable.getClass().getSimpleName());
            }
        }
        boolean checkpointRestored = false;
        if (checkpoints != null) {
            var run = runs.findById(runId).orElseThrow();
            var checkpoint = checkpoints.restore(run, stage);
            if (checkpoint != null) {
                checkpointRestored = true;
                set.revision = checkpoint.revision();
                set.sourceRevision = set.revision;
                set.repairAttempts = checkpoint.repairAttempts();
                set.resumedPlan = checkpoint.plan();
                for (var file : checkpoint.files()) {
                    set.touched.add(file.path());
                    set.baselines.put(file.path(), file.before());
                }
                // Outputs are proved, but validation on the new accepted/config context is still mandatory.
                set.validatedRevision = -1;
            }
        }
        active.put(runId, set);
        if (failedDrafts != null && !checkpointRestored) {
            var run = runs.findById(runId).orElseThrow();
            if (run.getRetrySourceRunId() != null)
                seedFailedDraft(run, set);
        }
        return stage;
    }

    private void seedFailedDraft(AgentRun run, ActiveChangeSet set) {
        var manifest = failedDrafts.retryManifest(run.getProjectId(), run.getRetrySourceRunId());
        var sources = failedDrafts.retrySources(run.getProjectId(), run.getRetrySourceRunId());
        try {
            for (var change : manifest.changes()) {
                if (change.after().equals(FileVersions.ABSENT))
                    files.delete(set.projectId, set.stage, change.path());
                else {
                    byte[] bytes = sources.get(change.path());
                    if (bytes == null || !change.after().equals("sha256:" + FailedDraftArchive.hash(bytes)))
                        throw new IllegalStateException("Retry source proof unavailable");
                    files.writeBytes(set.projectId, set.stage + "/" + change.path(), bytes);
                }
                if (!files.version(set.projectId, set.stage, change.path()).equals(change.after()))
                    throw new IllegalStateException("Retry staging proof failed");
                set.baselines.put(change.path(), change.before());
                set.touched.add(change.path());
            }
            changes.saveAllAndFlush(manifest.changes().stream().map(c -> FileChange.builder().runId(run.getId())
                    .projectId(run.getProjectId())
                    .path(c.path()).beforeVersion(c.before()).afterVersion(c.after())
                    .operation(c.after().equals(FileVersions.ABSENT) ? FileOperation.DELETE
                            : c.before().equals(FileVersions.ABSENT) ? FileOperation.CREATE : FileOperation.PATCH)
                    .build()).toList());
            set.revision++;
            set.sourceRevision = set.revision;
            set.validatedRevision = -1;
        } catch (RuntimeException uncertain) {
            set.batchRecoveryRequired = true;
            throw uncertain;
        }
    }

    /** Snapshot failure/Stop output before normal cleanup. A retention failure keeps raw stage evidence. */
    public void finish(AgentRun run) {
        var set = active.get(run.getId());
        if (set != null && set.batchRecoveryRequired) {
            active.remove(run.getId());
            log.warn("failed_draft_audit_pending runId={}", run.getId());
            return;
        }
        if (set != null && failedDrafts != null && FailedDraftService.eligible(run) && !set.touched.isEmpty()) {
            try {
                if (set.failedDraftBasis == null)
                    throw new IllegalStateException("Original draft basis unavailable; stage retained");
                Runnable retain = () -> failedDrafts
                        .retain(run, set.stage,
                                set.touched.stream().sorted()
                                        .map(path -> new FailedDraftArchive.Change(path, set.baselines.get(path),
                                                files.version(set.projectId, set.stage, path)))
                                        .toList(),
                                set.failedDraftBasis);
                if (leases == null)
                    retain.run();
                else
                    leases.guarded(set.projectId, retain);
            } catch (RuntimeException unavailable) {
                active.remove(run.getId());
                log.warn("failed_draft_retention_pending runId={} errorType={}", run.getId(),
                        unavailable.getClass().getSimpleName());
                return;
            }
        }
        discard(run.getId());
    }

    public MutationResult create(AgentContext context, String relative, String content) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String path = context.resolvePath(relative);
        UnitWriteScope.destination(context, path);
        requireAgentWritable(path);
        if (files.exists(set.projectId, set.stage, path))
            throw failure("FILE_ALREADY_EXISTS", "File already exists", path);
        ensureTextSize(content);
        rememberBaseline(set, path);
        write(set, path, content);
        return record(set, path, null, FileOperation.CREATE, FileVersions.ABSENT,
                files.version(set.projectId, set.stage, path));
    }

    /** New-file batch: preflight every entry, compensate proved writes, then commit all audit rows.
     * An uncertain audit/commit or cleanup blocks this stage; it must never become a checkpoint/save.
     */
    public List<MutationResult> createBatch(AgentContext context, List<NewFile> entries) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        synchronized (set) {
            requireRepairAvailable(set);
            if (entries == null || entries.isEmpty() || entries.size() > 24)
                throw new IllegalArgumentException("A batch must contain between 1 and 24 files");
            Map<String, NewFile> prepared = new LinkedHashMap<>();
            Set<String> portablePaths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, String> baselines = new LinkedHashMap<>();
            Set<String> attempted = new LinkedHashSet<>();
            boolean[] auditAttempted = { false };
            Supplier<Void> writeBatch = () -> {
                for (NewFile entry : entries) {
                    if (entry == null || entry.content() == null)
                        throw new IllegalArgumentException("Missing file content");
                    String path = Path.of(context.resolvePath(entry.path())).normalize().toString().replace('\\', '/');
                    path = context.resolvePath(path);
                    UnitWriteScope.destination(context, path);
                    requireAgentWritable(path);
                    if (!portablePaths.add(path))
                        throw failure("DUPLICATE_BATCH_PATH", "Batch paths must be distinct", path);
                    prepared.put(path, new NewFile(path, entry.content()));
                    ensureTextSize(entry.content());
                    if (files.exists(set.projectId, set.stage, path))
                        throw failure("FILE_ALREADY_EXISTS", "File already exists", path);
                    baselines.put(path, files.version(set.projectId, ".", path));
                }
                // Reject a file that would also be another entry's parent before any write.
                for (String path : prepared.keySet())
                    for (String other : prepared.keySet())
                        if (other.toLowerCase(Locale.ROOT).startsWith(path.toLowerCase(Locale.ROOT) + "/"))
                            throw failure("BATCH_PATH_CONFLICT", "A file cannot be another file's parent", path);
                try {
                    for (NewFile entry : prepared.values()) {
                        if (RunCancellation.current().isCancelled())
                            throw new LlmCancelledException();
                        attempted.add(entry.path()); // A write can succeed physically before reporting failure.
                        files.writeText(set.projectId, set.stage, entry.path(), entry.content());
                        if (!files.version(set.projectId, set.stage, entry.path()).equals(textVersion(entry.content())))
                            throw new IllegalStateException("Batch write proof failed");
                    }
                    for (NewFile entry : prepared.values())
                        if (!files.version(set.projectId, set.stage, entry.path()).equals(textVersion(entry.content())))
                            throw new IllegalStateException("Final batch proof failed");
                    if (RunCancellation.current().isCancelled())
                        throw new LlmCancelledException();
                } catch (RuntimeException failedWrite) {
                    try {
                        // Check every target before deleting any: preserve an unexpected edit.
                        for (String path : attempted) {
                            String actual = files.version(set.projectId, set.stage, path);
                            if (!actual.equals(FileVersions.ABSENT)
                                    && !actual.equals(textVersion(prepared.get(path).content())))
                                throw new IllegalStateException("Batch cleanup proof failed");
                        }
                        for (String path : attempted)
                            files.delete(set.projectId, set.stage, path);
                        for (String path : attempted)
                            if (!files.version(set.projectId, set.stage, path).equals(FileVersions.ABSENT))
                                throw new IllegalStateException("Batch cleanup incomplete");
                    } catch (RuntimeException uncertainCleanup) {
                        set.batchRecoveryRequired = true;
                        throw batchRecoveryRequired();
                    }
                    if (failedWrite instanceof LlmCancelledException)
                        throw failedWrite;
                    throw new AgentToolException("BATCH_WRITE_FAILED",
                            "New-file batch was rolled back; retry the complete batch");
                }
                auditAttempted[0] = true;
                changes.saveAllAndFlush(prepared.values().stream().map(entry -> FileChange.builder()
                        .runId(set.runId).projectId(set.projectId).path(entry.path()).operation(FileOperation.CREATE)
                        .beforeVersion(FileVersions.ABSENT).afterVersion(textVersion(entry.content())).build())
                        .toList());
                return null;
            };
            try {
                // Managed execution supplies one DB transaction and the project filesystem/lease lock.
                if (leases == null)
                    writeBatch.get();
                else
                    leases.guarded(set.projectId, writeBatch);
            } catch (RuntimeException error) {
                if (auditAttempted[0]) {
                    set.batchRecoveryRequired = true;
                    throw batchRecoveryRequired();
                }
                throw error;
            }
            set.baselines.putAll(baselines);
            set.touched.addAll(prepared.keySet());
            if (set.repairPending) {
                set.repairAttempts++;
                set.repairPending = false;
            }
            set.revision++;
            set.sourceRevision = set.revision;
            set.validatedRevision = -1;
            return prepared.values().stream().map(entry -> new MutationResult(entry.path(), null, FileOperation.CREATE,
                    FileVersions.ABSENT, textVersion(entry.content()), set.revision)).toList();
        }
    }

    private static String textVersion(String content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AgentToolException batchRecoveryRequired() {
        return new AgentToolException("BATCH_RECOVERY_REQUIRED",
                "Batch outcome is uncertain; discard this stage and recover from a proved checkpoint");
    }

    public record NewFile(String path, String content) {
    }

    /** Coordinator batch for existing/new/deleted files. Expected versions bind a complete wave snapshot. */
    public record Replacement(String path, String expectedVersion, String content) {
    }

    public void replaceBatch(AgentContext context, List<Replacement> entries) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        synchronized (set) {
            requireRepairAvailable(set);
            if (entries == null || entries.isEmpty() || entries.size() > 256)
                throw new IllegalArgumentException("Invalid coordinator batch size");
            var prepared = new LinkedHashMap<String, Replacement>();
            var originals = new LinkedHashMap<String, String>();
            var live = new LinkedHashMap<String, String>();
            var portable = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            long total = 0;
            for (var entry : entries) {
                if (entry == null)
                    throw new IllegalArgumentException("Missing batch entry");
                String path = context.resolvePath(entry.path());
                UnitWriteScope.destination(context, path);
                if (!path.equals(Path.of(path).normalize().toString().replace('\\', '/')) || !portable.add(path))
                    throw new IllegalArgumentException("Noncanonical/duplicate batch path");
                String before = files.version(set.projectId, set.stage, path);
                requireVersion(path, entry.expectedVersion(), before);
                if (!before.equals(FileVersions.ABSENT) && !files.regularFile(set.projectId, set.stage, path))
                    throw new IllegalArgumentException("Batch target is not a regular file");
                if (entry.content() != null) {
                    ensureTextSize(entry.content());
                    total += entry.content().getBytes(StandardCharsets.UTF_8).length;
                }
                if (total > 8_000_000)
                    throw new IllegalArgumentException("Batch byte limit exceeded");
                prepared.put(path, entry);
                originals.put(path, before);
                live.put(path, files.version(set.projectId, ".", path));
            }
            for (String path : prepared.keySet())
                for (String other : prepared.keySet())
                    if (other.toLowerCase(Locale.ROOT).startsWith(path.toLowerCase(Locale.ROOT) + "/"))
                        throw new IllegalArgumentException("Batch file/parent conflict");
            String backup = set.container + "/batches/" + UUID.randomUUID();
            var attempted = new LinkedHashSet<String>();
            boolean[] audit = { false };
            Runnable mutate = () -> {
                // Re-read every target under the shared mutation lock before touching any file.
                originals.forEach((p, v) -> requireVersion(p, v, files.version(set.projectId, set.stage, p)));
                originals.forEach((p, v) -> {
                    if (!v.equals(FileVersions.ABSENT)) {
                        files.copyFile(set.projectId, set.stage, p, backup, p);
                        requireVersion(p, v, files.version(set.projectId, backup, p));
                    }
                });
                try {
                    for (var e : prepared.values()) {
                        if (RunCancellation.current().isCancelled())
                            throw new LlmCancelledException();
                        attempted.add(e.path());
                        if (e.content() == null)
                            files.delete(set.projectId, set.stage, e.path());
                        else
                            files.writeText(set.projectId, set.stage, e.path(), e.content());
                    }
                    for (var e : prepared.values())
                        requireVersion(e.path(), e.content() == null ? FileVersions.ABSENT : textVersion(e.content()),
                                files.version(set.projectId, set.stage, e.path()));
                    if (RunCancellation.current().isCancelled())
                        throw new LlmCancelledException();
                } catch (RuntimeException failed) {
                    try {
                        for (String p : attempted) {
                            String current = files.version(set.projectId, set.stage, p),
                                    after = prepared.get(p).content() == null ? FileVersions.ABSENT
                                            : textVersion(prepared.get(p).content());
                            if (!current.equals(originals.get(p)) && !current.equals(after))
                                throw new IllegalStateException("Batch target changed during compensation");
                        }
                        for (String p : attempted) {
                            if (originals.get(p).equals(FileVersions.ABSENT))
                                files.delete(set.projectId, set.stage, p);
                            else {
                                requireVersion(p, originals.get(p), files.version(set.projectId, backup, p));
                                files.copyFile(set.projectId, backup, p, set.stage, p);
                            }
                        }
                        originals.forEach((p, v) -> requireVersion(p, v, files.version(set.projectId, set.stage, p)));
                    } catch (RuntimeException uncertain) {
                        set.batchRecoveryRequired = true;
                        throw batchRecoveryRequired();
                    }
                    throw failed;
                }
                audit[0] = true;
                changes.saveAllAndFlush(prepared.values().stream()
                        .map(e -> FileChange.builder().runId(set.runId).projectId(set.projectId).path(e.path())
                                .operation(e.content() == null ? FileOperation.DELETE
                                        : originals.get(e.path()).equals(FileVersions.ABSENT) ? FileOperation.CREATE
                                                : FileOperation.PATCH)
                                .beforeVersion(originals.get(e.path()))
                                .afterVersion(e.content() == null ? FileVersions.ABSENT : textVersion(e.content()))
                                .build())
                        .toList());
            };
            try {
                if (leases == null)
                    mutate.run();
                else
                    leases.guarded(set.projectId, mutate);
            } catch (RuntimeException e) {
                if (audit[0]) {
                    set.batchRecoveryRequired = true;
                    throw batchRecoveryRequired();
                }
                throw e;
            }
            live.forEach(set.baselines::putIfAbsent);
            set.touched.addAll(prepared.keySet());
            if (set.repairPending) {
                set.repairAttempts++;
                set.repairPending = false;
            }
            set.revision++;
            set.sourceRevision = set.revision;
            set.validatedRevision = -1;
        }
    }

    public MutationResult patch(AgentContext context, String relative, String expectedVersion, String oldText,
            String newText) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String path = context.resolvePath(relative);
        UnitWriteScope.destination(context, path);
        requireAgentWritable(path);
        requireRegular(set, path);
        String actual = files.version(set.projectId, set.stage, path);
        requireVersion(path, expectedVersion, actual);
        if (oldText == null || oldText.isEmpty())
            throw new AgentToolException("PATCH_TARGET_EMPTY", "oldText must contain a nonempty exact target",
                    Map.of("path", path, "actualVersion", actual, "action",
                            "Read the file and choose a unique nonempty block"));
        if (newText == null)
            throw new IllegalArgumentException("Missing replacement text");
        String content = readStagedText(set, path);
        int first = content.indexOf(oldText);
        if (first < 0)
            throw new AgentToolException("PATCH_TARGET_NOT_FOUND", "Expected patch target was not found",
                    Map.of("path", path, "actualVersion", actual, "action",
                            "Re-read the file and construct a new patch"));
        if (content.indexOf(oldText, first + 1) >= 0) {
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
        UnitWriteScope.destination(context, path);
        requireAgentWritable(path);
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

    public MutationResult rename(AgentContext context, String sourceRelative, String targetRelative,
            String expectedVersion) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String source = context.resolvePath(sourceRelative);
        String target = context.resolvePath(targetRelative);
        UnitWriteScope.destination(context, source);
        requireAgentWritable(source);
        UnitWriteScope.destination(context, target);
        requireAgentWritable(target);
        requireRegular(set, source);
        if (files.exists(set.projectId, set.stage, target))
            throw failure("RENAME_TARGET_EXISTS", "Rename target already exists", target);
        String actual = files.version(set.projectId, set.stage, source);
        requireVersion(source, expectedVersion, actual);
        rememberBaseline(set, source);
        rememberBaseline(set, target);
        try {
            files.move(set.projectId, set.stage, source, target);
        } catch (RuntimeException error) {
            throw new AgentToolException("RENAME_FAILED", "Could not rename staged file", Map.of("path", source));
        }
        return record(set, target, source, FileOperation.RENAME, actual,
                files.version(set.projectId, set.stage, target));
    }

    /**
     * Copies a staged file, binary included, to a new path: how an image the user uploaded
     * ({@code uploads/...}) becomes part of the site, e.g. {@code public/hero.png} in a Vite app.
     */
    public MutationResult copy(AgentContext context, String sourceRelative, String targetRelative) {
        ActiveChangeSet set = requireActive(context);
        requirePlan(context);
        requireRepairAvailable(set);
        String source = context.resolvePath(sourceRelative);
        String target = context.resolvePath(targetRelative);
        UnitWriteScope.source(context, source);
        UnitWriteScope.destination(context, target);
        requireAgentWritable(target);
        requireRegular(set, source);
        if (files.exists(set.projectId, set.stage, target))
            throw failure("FILE_ALREADY_EXISTS", "Copy target already exists", target);
        if (files.size(set.projectId, set.stage, source) > MAX_COPY_BYTES) {
            throw new AgentToolException("FILE_TOO_LARGE", "Files larger than 10 MB cannot be copied",
                    Map.of("path", source));
        }
        rememberBaseline(set, target);
        String sourceVersion = files.version(set.projectId, set.stage, source);
        try {
            files.copyFile(set.projectId, set.stage, source, set.stage, target);
            if (!sourceVersion.equals(files.version(set.projectId, set.stage, source))
                    || !sourceVersion.equals(files.version(set.projectId, set.stage, target)))
                throw new IllegalStateException("Asset source/destination proof failed");
        } catch (RuntimeException error) {
            if (files.exists(set.projectId, set.stage, target)) {
                set.batchRecoveryRequired = true;
                throw batchRecoveryRequired();
            }
            throw new AgentToolException("COPY_FAILED", "Could not copy staged file", Map.of("path", source));
        }
        try {
            return record(set, target, source, FileOperation.COPY, FileVersions.ABSENT, sourceVersion);
        } catch (RuntimeException uncertainAudit) {
            set.batchRecoveryRequired = true;
            throw batchRecoveryRequired();
        }
    }

    public ValidationResult validate(AgentContext context) {
        synchronizeBrief(context);
        ActiveChangeSet set = requireActive(context);
        List<ValidationIssue> issues = new ArrayList<>();
        for (String path : set.touched) {
            if (!files.regularFile(set.projectId, set.stage, path))
                continue;
            if (isBinary(files.readHead(set.projectId, set.stage, path, 8_192)))
                continue; // a copied image
            String text = files.readText(set.projectId, set.stage, path);
            if (text.contains("<<<<<<<") || text.contains(">>>>>>>")) {
                issues.add(new ValidationIssue(path, "MERGE_MARKER", "Unresolved merge marker"));
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(".json")) {
                try {
                    objectMapper.readTree(text);
                } catch (RuntimeException error) {
                    issues.add(new ValidationIssue(path, "INVALID_JSON", "JSON is not syntactically valid"));
                }
            }
        }
        Set<String> packageRoots = new LinkedHashSet<>();
        for (String path : set.touched)
            if (path.equals("package.json") || path.equals("package-lock.json")
                    || path.endsWith("/package.json") || path.endsWith("/package-lock.json"))
                packageRoots.add(path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".");
        for (String root : packageRoots) {
            String prefix = root.equals(".") ? "" : root + "/";
            String manifestPath = prefix + "package.json", lockPath = prefix + "package-lock.json";
            if (!files.regularFile(set.projectId, set.stage, manifestPath))
                continue;
            String packageRoot = root.equals(".") ? set.stage : set.stage + "/" + root;
            if (!new NodePack(objectMapper).inspect(files, set.projectId, packageRoot).packageManager().equals("npm"))
                continue;
            if (!files.regularFile(set.projectId, set.stage, lockPath)) {
                if (set.touched.contains(lockPath))
                    issues.add(new ValidationIssue(lockPath, "DEPENDENCY_LOCK_STALE",
                            "Call refreshDependencies to restore the package lock"));
                continue;
            }
            try {
                var manifest = objectMapper.readTree(readStagedText(set, manifestPath));
                var lockRoot = objectMapper.readTree(readStagedText(set, lockPath)).path("packages").path("");
                if (!lockRoot.isMissingNode() && !NpmLockfiles.matches(manifest, lockRoot))
                    issues.add(new ValidationIssue(lockPath, "DEPENDENCY_LOCK_STALE",
                            "Package dependencies changed; call refreshDependencies before validation"));
            } catch (RuntimeException invalid) {
                issues.add(new ValidationIssue(lockPath, "DEPENDENCY_LOCK_INVALID", "Package/lock JSON is unreadable"));
            }
        }
        boolean valid = issues.isEmpty() && !set.touched.isEmpty();
        return new ValidationResult(valid, set.revision, List.copyOf(issues),
                valid ? "Deterministic file validation passed" : "Fix validation issues and validate again");
    }

    /** Platform-owned brief blocks enter the same stage, archive and promotion proof as code. */
    private void synchronizeBrief(AgentContext context) {
        if (memory == null)
            return; // Focused legacy constructor fixtures; managed application injection is mandatory.
        ActiveChangeSet set = requireActive(context);
        if (set.touched.isEmpty())
            return;
        boolean[] writeAttempted = { false };
        Runnable update = () -> {
            String name = ProjectBriefFile.NAME;
            String beforeVersion = files.version(set.projectId, set.stage, name);
            if (!beforeVersion.equals(FileVersions.ABSENT) && files.size(set.projectId, set.stage, name) > 128_000)
                throw new AgentToolException("BRIEF_TOO_LARGE", "Private brief exceeds synchronization limit");
            String before = beforeVersion.equals(FileVersions.ABSENT) ? ""
                    : files.readText(set.projectId, set.stage, name);
            Map<String, String> versions = new TreeMap<>();
            for (String path : set.touched)
                if (!path.equals(name))
                    versions.put(path, files.version(set.projectId, set.stage, path));
            String after = memory.synchronize(set.projectId, set.runId, before, versions);
            if (after.equals(before))
                return;
            rememberBaseline(set, name);
            int repairs = set.repairAttempts;
            boolean pending = set.repairPending;
            int source = set.sourceRevision;
            try {
                writeAttempted[0] = true;
                files.writeText(set.projectId, set.stage, name, after);
                if (!files.version(set.projectId, set.stage, name).equals(textVersion(after)))
                    throw new IllegalStateException("Brief write proof failed");
                record(set, name, null,
                        beforeVersion.equals(FileVersions.ABSENT) ? FileOperation.CREATE : FileOperation.PATCH,
                        beforeVersion, textVersion(after));
                set.repairAttempts = repairs;
                set.repairPending = pending; // System metadata does not charge a model repair.
                set.sourceRevision = source; // nor invalidate compiler and browser results for the code.
            } catch (RuntimeException unknown) {
                set.batchRecoveryRequired = true;
                throw new AgentToolException("BRIEF_SYNC_RECOVERY_REQUIRED",
                        "Brief staging outcome is uncertain; recover from a proved checkpoint");
            }
        };
        try {
            if (leases == null)
                update.run();
            else
                leases.guarded(set.projectId, update);
        } catch (RuntimeException error) {
            if (writeAttempted[0])
                set.batchRecoveryRequired = true;
            throw error;
        }
    }

    public CommitResult commit(AgentContext context, AgentRun run) {
        return leases == null ? commitOwned(context, run)
                : leases.guarded(context.projectId(), () -> commitOwned(context, run));
    }

    private CommitResult commitOwned(AgentContext context, AgentRun run) {
        if (!Objects.equals(context.runId(), run.getId()) || !Objects.equals(context.projectId(), run.getProjectId())
                || !Objects.equals(context.userId(), run.getUserId()))
            throw new IllegalArgumentException("Save context does not match the accepted run");
        // A successful tool-time check does not authorize promotion after membership revocation.
        projects.requireCanEdit(context.userId(), context.projectId());
        ActiveChangeSet set = requireActive(context);
        if (set.touched.isEmpty()) {
            discard(run.getId());
            return new CommitResult(null, List.of());
        }
        if (set.validatedRevision != set.revision) {
            throw new AgentToolException("VALIDATION_REQUIRED", "Validate the current change set before finishing");
        }
        for (Map.Entry<String, String> baseline : set.baselines.entrySet()) {
            String actual = files.version(set.projectId, ".", baseline.getKey());
            if (!actual.equals(baseline.getValue())) {
                throw new AgentToolException("STALE_PROJECT_FILE",
                        "A project file changed while the agent was working; the change set was not committed",
                        Map.of("path", baseline.getKey(), "expectedVersion", baseline.getValue(), "actualVersion",
                                actual));
            }
        }
        String archiveRoot = HISTORY + "/" + run.getId();
        String archive = archiveRoot + "/original";
        List<PromotionJournal.FileState> proof = set.touched.stream().sorted()
                .map(path -> new PromotionJournal.FileState(path, set.baselines.get(path),
                        files.version(set.projectId, set.stage, path)))
                .toList();
        PromotionJournal.validate(proof);
        if (RunCancellation.current().isCancelled())
            throw new LlmCancelledException();
        boolean started;
        try {
            started = journal != null ? journal.prepare(run, set.revision, proof)
                    : runs.beginCommit(run.getId(), run.getProjectId(), Instant.now()) == 1;
        } catch (RuntimeException uncertain) {
            run.setStatus(RunStatus.COMMITTING);
            throw new PromotionRecoveryPendingException(uncertain);
        }
        if (!started)
            throw new LlmCancelledException();
        run.setStatus(RunStatus.COMMITTING);
        String snapshotRef = SNAPSHOT_PREFIX + run.getId();
        boolean applied = false;
        boolean finalizationAttempted = false;
        try {
            sandbox.copyTree(set.projectId, ".", archive);
            verifyVersions(set.projectId, archive, proof, true);
            if (journal != null)
                journal.ready(run.getId());
            else if (runs.recordSnapshot(run.getId(), snapshotRef) != 1)
                throw new IllegalStateException("Snapshot persistence refused");
            run.setSnapshotRef(snapshotRef);
            projects.requireCanEdit(context.userId(), context.projectId());
            // Commands cannot rewrite a certified staged file between manifest creation and promotion.
            verifyVersions(set.projectId, set.stage, proof, false);
            if (journal != null)
                journal.applying(run.getId());
            applied = true;
            for (var file : proof) {
                if (!file.after().equals(FileVersions.ABSENT))
                    files.copyFile(set.projectId, set.stage, file.path(), ".", file.path());
                else
                    files.delete(set.projectId, ".", file.path());
            }
            verifyVersions(set.projectId, ".", proof, false);
            sandbox.enforceQuota(set.projectId);
            if (savedBuilds != null) {
                try {
                    savedBuilds.capture(run.getId(), set.projectId);
                } catch (RuntimeException unavailable) {
                    // Missing history must never certify a partial version or discard an otherwise valid save.
                    log.warn("saved_build_snapshot_unavailable runId={} errorType={}",
                            run.getId(), unavailable.getClass().getSimpleName());
                }
            }
            Instant savedAt;
            finalizationAttempted = true;
            if (journal != null)
                savedAt = journal.saved(run.getId());
            else {
                savedAt = Instant.now();
                if (runs.recordCodeSaved(run.getId(), savedAt) != 1)
                    throw new IllegalStateException("Save finalization refused");
            }
            run.setCodeSavedAt(savedAt);
            run.setPreviewStatus("NOT_REQUESTED");
        } catch (RuntimeException error) {
            // A committed DB transaction may have lost its response. Read durable truth BEFORE compensation.
            if (journal != null) {
                PromotionJournal.Entry entry;
                try {
                    entry = journal.read(run.getId());
                    if (entry == null)
                        throw new IllegalStateException("Save evidence unavailable");
                } catch (RuntimeException unknown) {
                    throw new PromotionRecoveryPendingException(unknown);
                }
                if (entry.state().equals("SAVED")) {
                    run.setSnapshotRef(snapshotRef);
                    run.setCodeSavedAt(entry.savedAt());
                    run.setPreviewStatus("NOT_REQUESTED");
                } else {
                    try {
                        if (applied || Set.of("APPLYING", "ROLLING_BACK", "RECOVERY_REQUIRED").contains(entry.state()))
                            restoreVerified(entry);
                        else {
                            verifyVersions(set.projectId, ".", proof, true);
                            journal.recoveryState(run.getId(), "ROLLED_BACK");
                        }
                    } catch (RuntimeException uncertain) {
                        try {
                            journal.recoveryState(run.getId(), "RECOVERY_REQUIRED");
                        } catch (RuntimeException ignored) {
                        }
                        throw new PromotionRecoveryPendingException(uncertain);
                    }
                    throw new IllegalStateException("Code save failed; original touched files verified", error);
                }
            } else {
                // Focused unmanaged fixtures have no durable journal; ambiguous finalization must stay pending.
                var durable = runs.findById(run.getId()).orElse(null);
                if (durable != null && durable.getCodeSavedAt() != null) {
                    run.setCodeSavedAt(durable.getCodeSavedAt());
                    run.setPreviewStatus(durable.getPreviewStatus());
                } else if (applied && !finalizationAttempted) {
                    try {
                        restoreVerified(new PromotionJournal.Entry(run.getId(), set.projectId, null, 0, set.revision,
                                proof, "APPLYING", null));
                    } catch (RuntimeException uncertain) {
                        throw new PromotionRecoveryPendingException(uncertain);
                    }
                    throw new IllegalStateException("Code save failed; original touched files verified", error);
                } else if (applied)
                    throw new PromotionRecoveryPendingException(error);
                else
                    throw new IllegalStateException("Could not prepare code save; nothing was committed", error);
            }
        }
        List<String> changed = set.touched.stream().sorted().toList();
        active.remove(run.getId());
        try {
            sandbox.removeTree(set.projectId, set.container);
        } catch (RuntimeException ignored) {
        }
        pruneHistory(set.projectId, run.getId());
        if (savedBuilds != null) {
            try {
                savedBuilds.retireOldBodies(set.projectId);
            } catch (RuntimeException deferred) {
                log.warn("saved_build_cleanup_deferred project={} errorType={}",
                        set.projectId, deferred.getClass().getSimpleName());
            }
        }
        return new CommitResult(snapshotRef, changed);
    }

    /** The archive a snapshot reference points to, or null when the reference is not an archive. */
    public static String archiveFor(String snapshotRef) {
        if (snapshotRef == null)
            return null;
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
        if (set == null)
            return;
        if (journal != null) {
            try {
                var entry = journal.read(runId);
                if (entry != null && !entry.terminal())
                    return;
            } catch (RuntimeException unavailable) {
                return;
            }
        }
        try {
            sandbox.removeTree(set.projectId, set.container);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Each committed build archives the whole project (rollback restores it), and .codepal is outside
     * the quota. Only the latest build can be rolled back, so older archives are removed.
     */
    private void pruneHistory(UUID projectId, UUID keepRunId) {
        // Restore/promotion proofs and quarantined operations may still own these archives.
    }

    /**
     * Removes the staging area of a run whose process died: its in-memory change set is gone, so
     * {@link #discard} can no longer find it. Called by crash recovery after any restore.
     */
    public void cleanupAbandoned(UUID projectId, UUID runId) {
        if (active.containsKey(runId))
            return; // still owned by a live run in this process
        try {
            if (failedDrafts != null) {
                var run = runs.findByIdAndProjectId(runId, projectId).orElseThrow();
                if (FailedDraftService.eligible(run)) {
                    String container = CHANGESETS + "/" + runId;
                    var roots = files.list(projectId, container, 3).stream().filter(e -> e.directory()
                            && (e.path().equals("workspace") || e.path().matches("epoch-[0-9]+/workspace")))
                            .map(e -> container + "/" + e.path()).toList();
                    if (roots.size() > 1)
                        throw new IllegalStateException("Ambiguous abandoned stages retained");
                    if (roots.size() == 1) {
                        Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                        for (var change : changes.findByRunIdOrderByCreatedAtAsc(runId)) {
                            before.putIfAbsent(change.getPath(), change.getBeforeVersion());
                            after.put(change.getPath(), change.getAfterVersion());
                        }
                        String ownedContainer = roots.getFirst().substring(0,
                                roots.getFirst().length() - "/workspace".length());
                        failedDrafts.retain(run, roots.getFirst(),
                                before.entrySet().stream()
                                        .map(e -> new FailedDraftArchive.Change(e.getKey(), e.getValue(),
                                                after.get(e.getKey())))
                                        .toList(),
                                failedDrafts.readBasis(run, ownedContainer));
                    }
                }
            }
            sandbox.removeTree(projectId, CHANGESETS + "/" + runId);
        } catch (RuntimeException error) {
            log.warn("abandoned_changeset_cleanup_failed runId={} reason={}", runId, error.getMessage());
        }
    }

    private void verifyVersions(UUID project, String root, List<PromotionJournal.FileState> proof, boolean before) {
        for (var file : proof)
            if (!files.version(project, root, file.path()).equals(before ? file.before() : file.after()))
                throw new IllegalStateException("File proof does not match: " + file.path());
    }

    private void restoreVerified(PromotionJournal.Entry entry) {
        // Verify every source and target first; unknown edits are preserved and keep the quarantine.
        verifyVersions(entry.projectId(), entry.archive(), entry.files(), true);
        for (var file : entry.files()) {
            String current = files.version(entry.projectId(), ".", file.path());
            if (!current.equals(file.before()) && !current.equals(file.after()))
                throw new IllegalStateException("Recovery target changed: " + file.path());
        }
        if (journal != null)
            journal.recoveryState(entry.runId(), "ROLLING_BACK");
        for (var file : entry.files()) {
            if (files.version(entry.projectId(), ".", file.path()).equals(file.before()))
                continue;
            if (file.before().equals(FileVersions.ABSENT))
                files.delete(entry.projectId(), ".", file.path());
            else
                files.copyFile(entry.projectId(), entry.archive(), file.path(), ".", file.path());
        }
        verifyVersions(entry.projectId(), ".", entry.files(), true);
        if (journal != null)
            journal.recoveryState(entry.runId(), "ROLLED_BACK");
    }

    /** Called only by the inactive-project recovery coordinator under its trusted critical section. */
    public boolean recoverPromotion(AgentRun run) {
        if (leases != null)
            leases.requireRecovery(run.getProjectId());
        PromotionJournal.Entry entry = journal == null ? null : journal.read(run.getId());
        if (entry == null) {
            // Historical archives lack proof of completeness. Preserve evidence rather than destructive restore.
            if (run.getCodeSavedAt() == null
                    && (run.getSnapshotRef() != null || run.getStatus() == RunStatus.COMMITTING))
                throw new PromotionRecoveryPendingException(null);
            return false;
        }
        if (!entry.projectId().equals(run.getProjectId()))
            throw new PromotionRecoveryPendingException(null);
        if (entry.state().equals("SAVED")) {
            run.setCodeSavedAt(entry.savedAt());
            return false;
        }
        if (entry.state().equals("ROLLED_BACK"))
            return true;
        try {
            if (Set.of("PREPARING", "READY").contains(entry.state())) {
                verifyVersions(entry.projectId(), ".", entry.files(), true);
                journal.recoveryState(run.getId(), "ROLLED_BACK");
            } else
                restoreVerified(entry);
            return true;
        } catch (RuntimeException unknown) {
            try {
                journal.recoveryState(run.getId(), "RECOVERY_REQUIRED");
            } catch (RuntimeException ignored) {
            }
            throw new PromotionRecoveryPendingException(unknown);
        }
    }

    private MutationResult record(ActiveChangeSet set, String path, String source, FileOperation operation,
            String before, String after) {
        if (set.repairPending) {
            set.repairAttempts++;
            set.repairPending = false;
        }
        set.touched.add(path);
        // A rename changes its source too; a copy leaves it as it is.
        if (source != null && operation != FileOperation.COPY)
            set.touched.add(source);
        set.revision++;
        set.sourceRevision = set.revision;
        set.validatedRevision = -1;
        changes.save(FileChange.builder().runId(set.runId).projectId(set.projectId).path(path).sourcePath(source)
                .operation(operation).beforeVersion(before).afterVersion(after).build());
        return new MutationResult(path, source, operation, before, after, set.revision);
    }

    public String resumePlan(AgentContext context) {
        var set = requireActive(context);
        if (set.resumedPlan == null)
            return null;
        context.plan().restore(set.resumedPlan);
        return PromptLoader.load(Prompt.RESUME_PLAN).replace("{{revision}}", Integer.toString(set.revision))
                .replace("{{files}}", set.touched.toString()).replace("{{plan}}", set.resumedPlan.toString());
    }

    public boolean requeueCheckpoint(AgentRun run) {
        if (checkpoints == null || run.getMode() != ProjectType.BUILD
                || run.getStatus() != RunStatus.RUNNING || run.getCancelRequestedAt() != null)
            return false;
        try {
            return checkpoints.requeue(run);
        } catch (RuntimeException invalid) {
            log.warn("checkpoint_resume_rejected runId={} errorType={}", run.getId(),
                    invalid.getClass().getSimpleName());
            return false;
        }
    }

    public WorkspaceView view(AgentContext context) {
        ActiveChangeSet set = requireActive(context);
        return new WorkspaceView(".", set.stage, set.container, set.revision, List.copyOf(set.touched), set.sourceRevision);
    }

    /** Read-only view of the currently staged contents; never exposes the live workspace for editing. */
    public List<StagedFile> stagedFiles(UUID runId) {
        ActiveChangeSet set = active.get(runId);
        if (set == null)
            return List.of();
        return set.touched.stream().sorted().map(path -> {
            try {
                if (!files.regularFile(set.projectId, set.stage, path))
                    return new StagedFile(path, null, true);
                byte[] bytes = files.readHead(set.projectId, set.stage, path, 200_001);
                if (bytes.length > 200_000 || isBinary(bytes))
                    return new StagedFile(path, null, false);
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
        if (passed && certifyChangeSet)
            synchronizeBrief(context);
        ActiveChangeSet set = requireActive(context);
        set.lastDiagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (passed) {
            if (certifyChangeSet) {
                if (checkpoints != null && !set.touched.isEmpty()) {
                    var run = runs.findById(set.runId).orElseThrow();
                    List<PromotionJournal.FileState> proof = set.touched.stream().sorted()
                            .map(path -> new PromotionJournal.FileState(path, set.baselines.get(path),
                                    files.version(set.projectId, set.stage, path)))
                            .toList();
                    checkpoints.save(run, set.stage, set.revision, set.repairAttempts, context.plan().snapshot(),
                            proof);
                }
                set.validatedRevision = set.revision;
                set.repairPending = false;
            }
        } else {
            set.validatedRevision = -1;
            set.repairPending = true;
        }
        return progress(set);
    }

    public ValidationProgress validationProgress(AgentContext context) {
        return progress(requireActive(context));
    }

    public ValidationProgress recordTerminalValidationFailure(AgentContext context,
            List<ProjectDiagnostic> diagnostics) {
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
            throw new AgentToolException("REPAIR_LIMIT_EXCEEDED",
                    "Validation still fails and the repair limit is exhausted",
                    Map.of("repairAttempts", set.repairAttempts, "maxRepairAttempts", max,
                            "diagnostics", set.lastDiagnostics));
        }
    }

    private static void requirePlan(AgentContext context) {
        if (!context.plan().exists())
            throw new AgentToolException("PLANNING_REQUIRED", "Create a concrete plan before modifying files");
    }

    private ActiveChangeSet requireActive(AgentContext context) {
        ActiveChangeSet set = active.get(context.runId());
        if (set == null || !set.projectId.equals(context.projectId()))
            throw new AgentToolException("CHANGE_SET_NOT_ACTIVE", "No active change set for this run");
        if (set.batchRecoveryRequired)
            throw batchRecoveryRequired();
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
        for (byte value : bytes)
            if (value == 0)
                throw new AgentToolException("BINARY_FILE", "Binary files cannot be modified", Map.of("path", path));
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
        if (expected == null || expected.isBlank())
            throw new AgentToolException("EXPECTED_VERSION_REQUIRED", "An exact file version from readFile is required",
                    Map.of("path", path, "actualVersion", actual, "action",
                            "Call readFile and use its returned version"));
        if (!expected.equals(actual))
            throw new AgentToolException("STALE_FILE_VERSION", "File version no longer matches",
                    Map.of("path", path, "expectedVersion", expected, "actualVersion", actual, "action",
                            "Re-read the file before retrying"));
    }

    /**
     * A full-stack app's platform scaffold (accounts, record ownership, API wiring) is written only by its contract
     * build; an edit reads it but cannot weaken it.
     */
    private static void requireAgentWritable(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("server/src/rendra/") || lower.startsWith("client/src/rendra/"))
            throw failure("PLATFORM_FILE_READ_ONLY",
                    "Platform account, ownership and API files are read-only; change the app's own pages, components and routers",
                    path);
    }

    private static AgentToolException failure(String code, String message, String path) {
        return new AgentToolException(code, message, Map.of("path", path));
    }

    private static void ensureTextSize(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MUTATION_BYTES)
            throw new AgentToolException("FILE_TOO_LARGE", "Agent file mutations are limited to 1 MB");
    }

    private static final class ActiveChangeSet {
        final UUID runId;
        final UUID projectId;
        final String stage;
        final String container;
        final Map<String, String> baselines = new LinkedHashMap<>();
        final Set<String> touched = new LinkedHashSet<>();
        AgentPlanState.Snapshot resumedPlan;
        FailedDraftService.Basis failedDraftBasis;
        int revision;
        /** The revision of the last change other than the system brief: all that commands and the browser can observe. */
        int sourceRevision;
        int validatedRevision = -1;
        int repairAttempts;
        boolean repairPending;
        boolean batchRecoveryRequired;
        List<ProjectDiagnostic> lastDiagnostics = List.of();

        ActiveChangeSet(UUID runId, UUID projectId, String stage, String container) {
            this.runId = runId;
            this.projectId = projectId;
            this.stage = stage;
            this.container = container;
        }
    }

    public record MutationResult(String path, String sourcePath, FileOperation operation, String beforeVersion,
            String afterVersion, int changeSetRevision) {
    }

    public record ValidationIssue(String path, String code, String message) {
    }

    public record ValidationResult(boolean valid, int changeSetRevision, List<ValidationIssue> issues, String summary) {
    }

    public record CommitResult(String snapshotRef, List<String> changedPaths) {
    }

    /**
     * Project-relative roots: {@code liveRoot} is always {@code "."}. {@code sourceRevision} ignores the system brief's
     * own writes, so it keys results of commands and browser checks.
     */
    public record WorkspaceView(String liveRoot, String stagedRoot, String container, int revision,
            List<String> touchedPaths, int sourceRevision) {
        public WorkspaceView(String liveRoot, String stagedRoot, String container, int revision, List<String> touchedPaths) {
            this(liveRoot, stagedRoot, container, revision, touchedPaths, revision);
        }
    }

    public record StagedFile(String path, String content, boolean deleted) {
    }

    public record ValidationProgress(int revision, int validatedRevision, int repairAttempts,
            int repairAttemptsRemaining, boolean repairLimitExhausted,
            List<ProjectDiagnostic> diagnostics) {
    }

    private static boolean isBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++)
            if (bytes[i] == 0)
                return true;
        return false;
    }
}
