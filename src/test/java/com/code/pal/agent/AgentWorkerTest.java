package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.config.SandboxPolicy;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRuntime;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkerTest {

    private final AgentJobQueue queue = mock(AgentJobQueue.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final AgentWorker worker = new AgentWorker(new AgentProperties(), queue, runs, runtime,
            mock(SandboxPolicy.class));
    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void busyProjectParksTheRunWithoutBlockingTheWorker() {
        when(runs.findById(runId)).thenReturn(Optional.of(run(RunStatus.QUEUED)));
        when(queue.tryLock(projectId, runId)).thenReturn(false);

        long started = System.nanoTime();
        worker.process(runId);

        verify(queue).parkBehindLock(projectId, runId);
        verify(runtime, never()).run(any());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 100, "no sleep while the project is busy");
    }

    @Test
    void aRunAnotherWorkerAlreadyExecutedIsNotRunAgain() {
        // Two queue entries for one run: our first read saw QUEUED, but by the time we hold the lock
        // the other worker has finished it.
        when(runs.findById(runId)).thenReturn(Optional.of(run(RunStatus.QUEUED)), Optional.of(run(RunStatus.DONE)));
        when(queue.tryLock(projectId, runId)).thenReturn(true);

        worker.process(runId);

        verify(runtime, never()).run(any());
        verify(queue).unlockAndReleaseParked(projectId, runId);
    }

    @Test
    void finishedRunReleasesTheProjectAndItsParkedRuns() {
        when(runs.findById(runId)).thenReturn(Optional.of(run(RunStatus.QUEUED)));
        when(queue.tryLock(projectId, runId)).thenReturn(true);

        worker.process(runId);

        verify(runtime).run(any());
        verify(queue).unlockAndReleaseParked(projectId, runId);
    }

    @Test
    void releaseHappensEvenWhenTheRunThrows() {
        when(runs.findById(runId)).thenReturn(Optional.of(run(RunStatus.QUEUED)));
        when(queue.tryLock(projectId, runId)).thenReturn(true);
        when(runtime.run(any())).thenThrow(new IllegalStateException("boom"));

        try { worker.process(runId); } catch (IllegalStateException expected) { /* surfaced to the loop */ }

        verify(queue).unlockAndReleaseParked(projectId, runId);
    }

    private AgentRun run(RunStatus status) {
        return AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.CHAT).status(status).build();
    }
}
