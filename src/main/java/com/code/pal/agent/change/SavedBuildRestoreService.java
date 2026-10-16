package com.code.pal.agent.change;

import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.exception.AgentRunConflictException;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit code-only restore. A dirty workspace must be saved or exported before requesting it. */
@Service
@Slf4j
@RequiredArgsConstructor
public class SavedBuildRestoreService {
    private static final List<RunStatus> ACTIVE = List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.COMMITTING);
    private final ProjectLeaseService leases;
    private final SavedBuildStore store;
    private final RestoreJournal journal;
    private final ProjectFiles files;
    private final ProjectService projects;
    private final AgentRunRepository runs;
    private final BuildDecisions decisions;
    private final SandboxPort sandbox;

    public record Review(UUID targetRunId, String currentDigest, String targetDigest, int changedFiles) {}
    public record Result(UUID operationId, UUID targetRunId, String state) {}
    public record Version(UUID runId, Instant savedAt, int fileCount, String sourceDigest, String limitation) {}
    public record History(UUID currentRunId, List<Version> versions) {}
    private record Prepared(SavedBuildStore.Point target, SavedBuildManifest current, List<PromotionJournal.FileState> proof) {}

    public List<Version> list(Long actor, UUID project) {
        projects.requireManageable(actor, project);
        return store.list(project).stream().map(point -> new Version(point.runId(), point.savedAt(), point.manifest().files().size(),
                point.manifest().digest(), List.of("NONE", "BROWSER").contains(point.storage()) && "NONE".equals(point.auth())
                ? null : "Server data/authentication compatibility review is required")).toList();
    }

    public History history(Long actor, UUID project) {
        projects.requireManageable(actor, project);
        return new History(store.currentRunId(project), list(actor, project));
    }

    public Review review(Long actor, UUID project, UUID target) {
        projects.requireManageable(actor, project);
        AtomicReference<Review> result = new AtomicReference<>();
        leases.manualRestore(project, () -> {
            var prepared = prepare(actor, project, target);
            result.set(new Review(target, prepared.current().digest(), prepared.target().manifest().digest(),
                    (int) prepared.proof().stream().filter(file -> !file.before().equals(file.after())).count()));
        });
        return result.get();
    }

    /** operationId is the client-generated stable request id; retries return evidence, never reapply it. */
    public Result restore(Long actor, UUID project, UUID target, UUID operationId, String expectedDigest) {
        projects.requireManageable(actor, project);
        if (operationId == null || expectedDigest == null) {
            throw conflict("Restore needs a reviewed code digest and a stable operation id");
        }
        var previous = journal.read(operationId);
        if (previous != null) {
            return replay(previous, actor, project, target, expectedDigest);
        }
        AtomicReference<Result> result = new AtomicReference<>();
        leases.manualRestore(project, () -> {
            var accepted = journal.read(operationId);
            if (accepted != null) {
                result.set(replay(accepted, actor, project, target, expectedDigest));
                return;
            }
            var prepared = prepare(actor, project, target);
            if (!prepared.current().digest().equals(expectedDigest)) {
                throw conflict("Code changed since restore review; review this version again");
            }
            apply(actor, project, operationId, expectedDigest, prepared);
            result.set(new Result(operationId, target, "RESTORED"));
        });
        return result.get();
    }

    public Result operation(Long actor, UUID project, UUID operationId) {
        projects.requireManageable(actor, project);
        var entry = journal.read(operationId);
        if (entry == null || !project.equals(entry.projectId()) || entry.files() == null) {
            throw conflict("Saved build restore operation not found");
        }
        return new Result(entry.id(), entry.runId(), entry.state());
    }

    private Prepared prepare(Long actor, UUID project, UUID target) {
        projects.requireManageable(actor, project);
        if (!runs.findByProjectIdAndStatusIn(project, ACTIVE).isEmpty()) {
            throw conflict("Stop or finish the active build before restoring code");
        }
        var point = store.find(project, target);
        if (point == null) {
            throw conflict("This build has no available saved AFTER version; old before-only archives cannot prove it");
        }
        var state = decisions.state(project);
        var choice = state.choice();
        if (state.blocked() || choice == null || choice.auth() != BuildDecisions.Auth.NONE
                || !List.of(BuildDecisions.Storage.NONE, BuildDecisions.Storage.BROWSER).contains(choice.storage())
                || !choice.storage().name().equals(point.storage()) || !"NONE".equals(point.auth())
                || files.exists(project, ".", "server/package.json")
                || point.manifest().files().stream().anyMatch(file -> file.path().equalsIgnoreCase("server/package.json"))) {
            throw conflict("Code restore needs compatible frontend storage decisions; server data and authentication are never restored");
        }
        var baseline = store.current(project);
        var current = SavedBuildManifest.scan(files, project, ".");
        if (baseline == null || !baseline.equals(current)) {
            throw conflict("Uncommitted or unproved code exists; save current work in a build before restoring");
        }
        if (!point.manifest().equals(SavedBuildManifest.scan(files, project, point.sourceRoot()))) {
            throw conflict("Saved build source changed or is unavailable; restore refused");
        }
        var before = new TreeMap<String, String>();
        var after = new TreeMap<String, String>();
        point.manifest().files().forEach(file -> before.put(file.path(), file.version()));
        current.files().forEach(file -> after.put(file.path(), file.version()));
        var paths = new TreeMap<String, Boolean>();
        before.keySet().forEach(path -> paths.put(path, true));
        after.keySet().forEach(path -> paths.put(path, true));
        var portable = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        if (!paths.keySet().stream().allMatch(portable::add)) {
            throw conflict("Case-conflicting versions cannot be safely restored");
        }
        var proof = paths.keySet().stream().map(path -> new PromotionJournal.FileState(path,
                before.getOrDefault(path, FileVersions.ABSENT), after.getOrDefault(path, FileVersions.ABSENT))).toList();
        PromotionJournal.validate(proof);
        verify(project, ".", proof, false);
        verify(project, point.sourceRoot(), proof, true);
        return new Prepared(point, current, proof);
    }

    private void apply(Long actor, UUID project, UUID id, String digest, Prepared prepared) {
        String backup = ".codepal/restore/" + id;
        var proof = prepared.proof();
        // PREPARING is durable before even the backup copy. Recovery can safely abandon an incomplete backup.
        journal.prepareSaved(id, prepared.target().runId(), project, actor, digest, proof);
        boolean applying = false;
        boolean terminalProved = false;
        try {
            for (var file : proof) {
                if (!file.after().equals(FileVersions.ABSENT)) {
                    files.copyFile(project, ".", file.path(), backup, file.path());
                }
            }
            verify(project, backup, proof, false);
            verify(project, ".", proof, false);
            verify(project, prepared.target().sourceRoot(), proof, true);
            projects.requireManageable(actor, project);
            journal.state(id, "APPLYING");
            applying = true;
            copy(project, prepared.target().sourceRoot(), proof, true);
            if (!SavedBuildManifest.scan(files, project, ".").equals(prepared.target().manifest())) {
                throw conflict("Restored code does not match the saved build");
            }
            journal.state(id, "RESTORED");
            terminalProved = true;
        } catch (RuntimeException failed) {
            boolean restored = compensate(project, id, backup, prepared, applying);
            terminalProved = true;
            if (restored) {
                return;
            }
            throw conflict("Restore failed; the reviewed code was retained. Review again before a new restore");
        } finally {
            if (terminalProved) {
                try {
                    sandbox.removeTree(project, backup);
                } catch (RuntimeException deferred) {
                    log.warn("saved_restore_backup_cleanup_deferred restoreId={} errorType={}",
                            id, deferred.getClass().getSimpleName());
                }
            }
        }
    }

    private boolean compensate(UUID project, UUID id, String backup, Prepared prepared, boolean applying) {
        try {
            if ("RESTORED".equals(journal.readState(id))) {
                verify(project, ".", prepared.proof(), true);
                return true;
            }
            if (applying) {
                verify(project, backup, prepared.proof(), false);
                // Never replace a write that cannot be attributed to either side of this operation.
                for (var file : prepared.proof()) {
                    String actual = files.version(project, ".", file.path());
                    if (!actual.equals(file.before()) && !actual.equals(file.after())) {
                        throw conflict("Restore has an unproven concurrent write");
                    }
                }
                copy(project, backup, prepared.proof(), false);
            }
            verify(project, ".", prepared.proof(), false);
            journal.state(id, "ROLLED_BACK");
            return false;
        } catch (RuntimeException unknown) {
            try {
                journal.state(id, "RECOVERY_REQUIRED");
            } catch (RuntimeException unavailable) {
                // PREPARING/APPLYING is already durable and also blocks new writes until recovery.
            }
            throw conflict("Restore outcome needs recovery; code and backups are retained");
        }
    }

    private void copy(UUID project, String root, List<PromotionJournal.FileState> proof, boolean desired) {
        for (var file : proof) {
            String version = desired ? file.before() : file.after();
            if (version.equals(FileVersions.ABSENT)) {
                files.delete(project, ".", file.path());
            } else {
                files.copyFile(project, root, file.path(), ".", file.path());
            }
        }
    }

    private void verify(UUID project, String root, List<PromotionJournal.FileState> proof, boolean desired) {
        for (var file : proof) {
            if (!files.version(project, root, file.path()).equals(desired ? file.before() : file.after())) {
                throw conflict("Code or backup proof changed; restore refused");
            }
        }
    }

    private Result replay(RestoreJournal.Entry entry, Long actor, UUID project, UUID target, String digest) {
        if (entry.files() == null || !project.equals(entry.projectId()) || !target.equals(entry.runId())
                || !Objects.equals(actor, entry.actorId()) || !digest.equals(entry.expectedDigest())) {
            throw conflict("Restore operation id belongs to a different review");
        }
        return new Result(entry.id(), entry.runId(), entry.state());
    }

    private static AgentRunConflictException conflict(String message) {
        return new AgentRunConflictException(message);
    }
}
