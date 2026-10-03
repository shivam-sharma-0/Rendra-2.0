package com.code.pal.agent.web;

import com.code.pal.agent.AgentEvents;
import com.code.pal.agent.change.FileChangeRepository;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.dto.AgentRunResponse;
import com.code.pal.agent.dto.AgentStepResponse;
import com.code.pal.agent.dto.FileChangeResponse;
import com.code.pal.agent.exception.AgentRunConflictException;
import com.code.pal.agent.exception.AgentRunNotFoundException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.repository.AgentStepRepository;
import com.code.pal.agent.snapshot.WorkspaceSnapshotService;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Replay source for reconnecting clients and the future agent timeline UI. */
@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs")
@RequiredArgsConstructor
public class AgentRunController {
    private final ProjectService projects;
    private final AgentRunRepository runs;
    private final AgentStepRepository steps;
    private final WorkspaceStorage workspace;
    private final WorkspaceSnapshotService snapshots;
    private final SandboxPort sandbox;
    private final AgentEvents events;
    private final AgentJobQueue queue;
    private final FileChangeRepository fileChanges;
    private final ChangeSetService changeSets;

    @GetMapping
    public List<AgentRunResponse> list(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        projects.requireAccessible(principal.userId(), projectId);
        return runs.findTop100ByProjectIdOrderByCreatedAtDesc(projectId).stream().map(AgentRunResponse::from).toList();
    }

    @GetMapping("/{runId}/steps")
    public List<AgentStepResponse> replay(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                          @PathVariable UUID runId, @RequestParam(defaultValue = "-1") int afterSeq) {
        projects.requireAccessible(principal.userId(), projectId);
        requireRun(projectId, runId);
        return steps.findByRunIdAndSeqGreaterThanOrderBySeqAsc(runId, Math.max(-1, afterSeq)).stream()
                .map(AgentStepResponse::from).toList();
    }

    /** Structured mutation ledger for auditing and exact UI change summaries; never returns file contents. */
    @GetMapping("/{runId}/changes")
    public List<FileChangeResponse> changes(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID projectId, @PathVariable UUID runId) {
        projects.requireAccessible(principal.userId(), projectId);
        requireRun(projectId, runId);
        return fileChanges.findByRunIdOrderByCreatedAtAsc(runId).stream().map(FileChangeResponse::from).toList();
    }

    /** Read-only contents of files changed in a currently running isolated change set. */
    @GetMapping("/{runId}/staged-files")
    public List<ChangeSetService.StagedFile> stagedFiles(@AuthenticationPrincipal AuthPrincipal principal,
                                                          @PathVariable UUID projectId, @PathVariable UUID runId) {
        projects.requireAccessible(principal.userId(), projectId);
        requireRun(projectId, runId);
        return changeSets.stagedFiles(runId);
    }

    /** Restore the last BUILD run's pre-run workspace snapshot. Project management permission is required. */
    @PostMapping("/{runId}/rollback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rollback(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID runId) {
        projects.requireManageable(principal.userId(), projectId);
        AgentRun run = requireRun(projectId, runId);
        if (run.getMode() != ProjectType.BUILD || run.getSnapshotRef() == null) {
            throw new AgentRunConflictException("This run has no workspace snapshot");
        }
        AgentRun latest = runs.findTopByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
        if (latest == null || !latest.getId().equals(runId) || run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.QUEUED) {
            throw new AgentRunConflictException("Only the latest completed build can be rolled back");
        }
        if (run.getSnapshotRef().startsWith("docker:")) {
            sandbox.restoreTree(projectId, ".codepal/history/" + run.getId() + "/original");
        } else {
            snapshots.restore(workspace.ensure(projectId), run.getSnapshotRef());
        }
        events.emit(run, AgentStepType.STATUS, "Workspace restored to the snapshot before this run.", null);
    }

    /** Request cancellation of a queued/running agent turn. The worker stops at its next checkpoint. */
    @PostMapping("/{runId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID runId) {
        projects.requireAccessible(principal.userId(), projectId);
        AgentRun run = requireRun(projectId, runId);
        RunStatus status = run.getStatus();
        if (status == RunStatus.DONE || status == RunStatus.FAILED || status == RunStatus.CANCELLED) {
            throw new AgentRunConflictException("This run has already finished");
        }
        // The runner checks this flag before every step (and right after it starts), so a queued run is
        // cancelled the moment it begins and a running one stops at its next iteration.
        queue.requestCancel(runId);
    }

    private AgentRun requireRun(UUID projectId, UUID runId) {
        return runs.findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new AgentRunNotFoundException("Agent run not found"));
    }
}
