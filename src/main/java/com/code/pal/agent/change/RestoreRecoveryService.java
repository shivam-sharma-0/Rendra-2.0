package com.code.pal.agent.change;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** Reconciles interrupted manual restores using only durable, hash-proven touched paths. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RestoreRecoveryService {
    private final ProjectLeaseService leases;
    private final RestoreJournal journal;
    private final PromotionJournal promotions;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final SiteBuildService sites;

    public void recoverPending(long deadlineNanos) {
        for (RestoreJournal.Entry pending : journal.pending()) {
            if (System.nanoTime() > deadlineNanos) {
                return;
            }
            try {
                leases.recoveringRestore(pending.projectId(), () -> recover(pending));
            } catch (RuntimeException unavailable) {
                log.warn("restore_recovery_pending restoreId={} project={} errorType={}",
                        pending.id(), pending.projectId(), unavailable.getClass().getSimpleName());
            }
        }
    }

    private void recover(RestoreJournal.Entry pending) {
        if (!sandbox.reconcilePendingOperations(pending.projectId()) || leases.hasPendingIo(pending.projectId())) {
            throw new IllegalStateException("Restore waits for verified process cleanup");
        }
        RestoreJournal.Entry entry = journal.read(pending.id());
        if (entry == null || List.of("RESTORED", "ROLLED_BACK").contains(entry.state())) {
            return;
        }
        if (!entry.equals(pending) || !entry.backup().equals(".codepal/restore/" + entry.id())) {
            throw new IllegalStateException("Restore evidence changed or has an invalid backup path");
        }
        if (entry.state().equals("PREPARING")) {
            finish(entry, "ROLLED_BACK");
            return;
        }
        if (entry.state().equals("RECOVERY_REQUIRED")) {
            // Back off this attempt even if its promotion proof or filesystem is unavailable below.
            journal.state(entry.id(), "RECOVERY_REQUIRED");
        }

        PromotionJournal.Entry proof = promotions.read(entry.runId());
        if (proof == null || !proof.projectId().equals(entry.projectId()) || !proof.state().equals("SAVED")) {
            throw new IllegalStateException("Restore promotion proof is unavailable");
        }
        PromotionJournal.validate(proof.files());
        // Promotion.before is the desired restored code; Promotion.after is the pre-restore live code.
        if (matches(entry, proof.files(), true)) {
            finish(entry, "RESTORED");
            return;
        }
        if (matches(entry, proof.files(), false)) {
            finish(entry, "ROLLED_BACK");
            return;
        }

        for (PromotionJournal.FileState file : proof.files()) {
            rollBackProvenFile(entry, file);
        }
        if (matches(entry, proof.files(), false)) {
            finish(entry, "ROLLED_BACK");
        } else {
            journal.state(entry.id(), "RECOVERY_REQUIRED");
            log.warn("restore_recovery_quarantined restoreId={} project={}", entry.id(), entry.projectId());
        }
    }

    private boolean matches(RestoreJournal.Entry entry, List<PromotionJournal.FileState> proof, boolean restored) {
        return proof.stream().allMatch(file -> files.version(entry.projectId(), ".", file.path())
                .equals(restored ? file.before() : file.after()));
    }

    private void rollBackProvenFile(RestoreJournal.Entry entry, PromotionJournal.FileState file) {
        String current = files.version(entry.projectId(), ".", file.path());
        if (current.equals(file.after()) || !current.equals(file.before())) {
            return;
        }
        if (file.after().equals(FileVersions.ABSENT)) {
            files.delete(entry.projectId(), ".", file.path());
        } else if (files.version(entry.projectId(), entry.backup(), file.path()).equals(file.after())) {
            files.copyFile(entry.projectId(), entry.backup(), file.path(), ".", file.path());
        }
    }

    private void finish(RestoreJournal.Entry entry, String state) {
        journal.state(entry.id(), state);
        try {
            sandbox.removeTree(entry.projectId(), entry.backup());
        } catch (RuntimeException deferred) {
            log.warn("restore_backup_cleanup_deferred restoreId={} errorType={}",
                    entry.id(), deferred.getClass().getSimpleName());
        }
        if (state.equals("RESTORED")) {
            sites.buildLater(entry.projectId());
        }
    }
}
