package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.testsupport.QueuedRequestFixture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHeartbeatGraceTest {
    @Test
    void transientRenewalExceptionThenSuccessNeverLosesOwnership() throws Exception {
        try (var fixture = new HeartbeatFixture()) {
            var signal = spy(new RunCancellation(fixture.fence.runId()));
            when(fixture.leases.renew(fixture.fence)).thenReturn(true)
                    .thenThrow(new IllegalStateException("DB temporarily unavailable")).thenReturn(true);
            fixture.start(signal);

            fixture.tickAt(2_000);
            fixture.tickAt(4_000);
            fixture.tickAt(6_000);

            assertAll(
                    () -> assertFalse(signal.isCancelled(), "A recovered renewal must leave the running worker active"),
                    () -> verify(signal, never()).loseOwnership(),
                    () -> verify(fixture.queue, times(2)).renewLock(fixture.fence.projectId(), fixture.fence.ownerId()));
        }
    }

    @Test
    void repeatedRenewalExceptionsFailAtTheLastSuccessfulRenewalDeadlineNeverAsCancelled() throws Exception {
        try (var fixture = new HeartbeatFixture()) {
            var beforeExpiryLost = new AtomicBoolean();
            var expiredSignal = new AtomicReference<RunCancellation>();
            when(fixture.leases.renew(fixture.fence)).thenReturn(true)
                    .thenThrow(new IllegalStateException("DB still unavailable"));
            LlmClient model = (request, delta) -> {
                fixture.tickAt(2_000); // Last successful renewal; the initial lease time is no longer the origin.
                fixture.tickAt(4_000);
                beforeExpiryLost.set(RunCancellation.current().isOwnershipLost());
                fixture.tickAt(29_999);
                beforeExpiryLost.set(beforeExpiryLost.get() || RunCancellation.current().isOwnershipLost());
                fixture.tickAt(30_000); // 2,000 + leaseMs(30,000) - interval(2,000).
                expiredSignal.set(RunCancellation.current());
                throw new LlmCancelledException();
            };
            AgentRun run = spy(AgentRun.builder().id(fixture.fence.runId()).projectId(fixture.fence.projectId())
                    .userId(7L).mode(ProjectType.CHAT).messageId("heartbeat-answer").status(RunStatus.QUEUED).build());
            var runner = runner(fixture, model, run);

            try (var ignored = RunFence.bind(fixture.fence)) {
                var result = runner.run(run);
                assertAll(
                        () -> assertFalse(beforeExpiryLost.get(), "Renewal exceptions must not stop an unexpired DB owner"),
                        () -> assertTrue(expiredSignal.get().isOwnershipLost(), "Grace expiry must stop ownership"),
                        () -> assertEquals(RunStatus.FAILED, result.status()),
                        () -> assertEquals("Worker ownership expired or changed", run.getError()),
                        () -> verify(run, never()).setStatus(RunStatus.CANCELLED));
            }
        }
    }

    @Test
    void explicitRenewalRejectionLosesOwnershipImmediatelyWithoutWaitingForGrace() throws Exception {
        try (var fixture = new HeartbeatFixture()) {
            var signal = spy(new RunCancellation(fixture.fence.runId()));
            when(fixture.leases.renew(fixture.fence)).thenReturn(false);
            fixture.start(signal);
            fixture.tickAt(2_000);

            assertTrue(signal.isOwnershipLost());
            verify(signal).loseOwnership();
            verify(fixture.queue, never()).renewLock(any(), any());
        }
    }

    private static AgentRunner runner(HeartbeatFixture fixture, LlmClient model, AgentRun run) {
        UUID projectId = run.getProjectId();
        Project project = Project.builder().id(projectId).ownerId(7L)
                .name("Heartbeat fixture").type(ProjectType.CHAT).build();
        var runs = mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        var messages = mock(MessageRepository.class);
        QueuedRequestFixture.stubOriginal(messages, run,
                Message.user(projectId, "heartbeat-user", "hi", ProjectType.CHAT, 7L));
        var projects = mock(ProjectService.class);
        when(projects.requireCanEdit(7L, projectId)).thenReturn(project);
        return new AgentRunner(model, new ToolRegistry(List.of()), mock(AgentToolExecutor.class),
                mock(AgentEvents.class), fixture.properties, mock(ProjectContextService.class), runs, projects,
                messages, mock(RealtimeService.class), mock(ProjectFiles.class), fixture.heartbeat, fixture.queue,
                new ObjectMapper(), mock(ChangeSetService.class), mock(ProjectValidationService.class),
                mock(NotificationService.class));
    }

    private static final class HeartbeatFixture implements AutoCloseable {
        private final AgentProperties properties = new AgentProperties();
        private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
        private final AgentJobQueue queue = mock(AgentJobQueue.class);
        private final RunFence fence = new RunFence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicReference<Runnable> scheduled = new AtomicReference<>();
        private final AgentHeartbeat heartbeat;

        private HeartbeatFixture() {
            properties.setHeartbeatIntervalMs(2_000);
            properties.getModels().setChat(List.of("scripted-model"));
            var scheduler = mock(ScheduledExecutorService.class);
            var future = mock(ScheduledFuture.class);
            doAnswer(call -> {
                scheduled.set(call.getArgument(0));
                return future;
            }).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
            try (var executors = mockStatic(Executors.class)) {
                executors.when(() -> Executors.newSingleThreadScheduledExecutor(any(ThreadFactory.class))).thenReturn(scheduler);
                heartbeat = new AgentHeartbeat(queue, properties, leases) {
                    @Override
                    protected long nanoTime() {
                        return nanos.get();
                    }
                };
            }
        }

        private void start(RunCancellation signal) throws Exception {
            try (var ignoredFence = RunFence.bind(fence); var ignoredSignal = RunCancellation.bind(signal)) {
                heartbeat.start(fence.runId(), fence.projectId());
            }
        }

        private void tickAt(long millis) {
            nanos.set(TimeUnit.MILLISECONDS.toNanos(millis));
            scheduled.get().run();
        }

        @Override
        public void close() {
            heartbeat.shutdown();
        }
    }
}
