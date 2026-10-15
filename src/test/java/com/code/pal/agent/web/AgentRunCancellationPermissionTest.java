package com.code.pal.agent.web;

import com.code.pal.agent.AgentCancellations;
import com.code.pal.agent.AgentEvents;
import com.code.pal.agent.activity.AgentActivityMapper;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.FileChangeRepository;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.exception.AgentRunConflictException;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.repository.AgentStepRepository;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.workspace.exception.WorkspaceAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunCancellationPermissionTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentJobQueue queue = mock(AgentJobQueue.class);
    private final AgentCancellations cancellations = mock(AgentCancellations.class);
    private final AgentRunController controller = new AgentRunController(projects, runs,
            mock(AgentStepRepository.class), mock(SandboxPort.class), mock(AgentEvents.class), queue,
            mock(FileChangeRepository.class), mock(ChangeSetService.class), mock(SiteBuildService.class),
            mock(AgentActivityMapper.class), cancellations);
    private final UUID project = UUID.randomUUID(), runId = UUID.randomUUID();
    private final AuthPrincipal actor = new AuthPrincipal(8L, UUID.randomUUID(), "fixture@example.test", "USER");

    @Test void viewerCannotStopOrWriteAnAudit() {
        doThrow(new WorkspaceAccessDeniedException()).when(projects).requireCanEdit(8L, project);
        assertThrows(WorkspaceAccessDeniedException.class, () -> controller.cancel(actor, project, runId));
        verifyNoInteractions(runs, queue, cancellations);
    }

    @Test void editorStopRecordsActorBeforeSignallingAnotherUsersRun() {
        var run = AgentRun.builder().id(runId).projectId(project).userId(7L).status(RunStatus.RUNNING).build();
        when(runs.findByIdAndProjectId(runId, project)).thenReturn(Optional.of(run));
        when(runs.recordCancellation(eq(runId),eq(project),eq(8L),any())).thenReturn(1);
        controller.cancel(actor, project, runId);
        var order = inOrder(runs, queue, cancellations);
        order.verify(runs).findByIdAndProjectId(runId, project);
        order.verify(runs).recordCancellation(eq(runId), eq(project), eq(8L), any());
        order.verify(cancellations).cancelLocal(runId);
        order.verify(queue).requestCancel(runId);
    }
    @Test void losingTheSaveRaceSendsNoCancellationSignal() {
        var running=AgentRun.builder().id(runId).projectId(project).status(RunStatus.RUNNING).build();
        var saving=AgentRun.builder().id(runId).projectId(project).status(RunStatus.COMMITTING).build();
        when(runs.findByIdAndProjectId(runId,project)).thenReturn(Optional.of(running),Optional.of(saving));
        assertThrows(AgentRunConflictException.class,()->controller.cancel(actor,project,runId));
        verifyNoInteractions(queue,cancellations);
    }
    @Test void acceptedStopStillSignalsLocallyWhenRedisIsDown() {
        when(runs.findByIdAndProjectId(runId,project)).thenReturn(Optional.of(AgentRun.builder().id(runId).projectId(project).status(RunStatus.RUNNING).build()));
        when(runs.recordCancellation(eq(runId),eq(project),eq(8L),any())).thenReturn(1);
        doThrow(new IllegalStateException("Redis unavailable")).when(queue).requestCancel(runId);
        assertDoesNotThrow(()->controller.cancel(actor,project,runId));verify(cancellations).cancelLocal(runId);
    }

    @Test void terminalRunRejectsStopWithoutChangingAudit() {
        when(runs.findByIdAndProjectId(runId, project)).thenReturn(Optional.of(
                AgentRun.builder().id(runId).projectId(project).status(RunStatus.DONE).build()));
        assertThrows(AgentRunConflictException.class, () -> controller.cancel(actor, project, runId));
        verify(runs, never()).recordCancellation(any(), any(), any(), any());
        verifyNoInteractions(queue, cancellations);
    }
}
