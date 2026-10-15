package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.RestoreRecoveryService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.realtime.RealtimeService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRecoverySchedulingTest {
    private final AtomicLong nanos = new AtomicLong();
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final RestoreRecoveryService restores = mock(RestoreRecoveryService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ChangeSetService changes = mock(ChangeSetService.class);
    private final SandboxPort sandbox = mock(SandboxPort.class);
    private final AgentRun promotion = AgentRun.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID())
            .messageId("interrupted-promotion").userId(7L).status(RunStatus.COMMITTING).build();

    @Test
    void manyQuarantinesCannotRunAheadOfAnotherProjectsPromotion() {
        var order = new ArrayList<String>();
        var restoredRows = new AtomicInteger();
        preparePromotion(() -> order.add("promotion"));
        doAnswer(call -> {
            long deadline = call.getArgument(0);
            order.add("restores");
            // A fake 100-row quarantine backlog: no filesystem work and no real waiting.
            for (int row = 0; row < 100 && nanos.get() < deadline; row++) {
                restoredRows.incrementAndGet();
                nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(30));
            }
            return null;
        }).when(restores).recoverPending(anyLong());

        recovery().sweep();

        assertAll(
                () -> assertEquals(List.of("promotion", "restores"), order,
                        "A restore backlog must not consume promotion recovery's opportunity"),
                () -> assertEquals(RunStatus.FAILED, promotion.getStatus(), "The other project's promotion must be recovered"),
                () -> assertTrue(restoredRows.get() > 0 && restoredRows.get() < 100,
                        "Restore processing must respect its own bounded fake-time budget"));
        verify(changes).recoverPromotion(promotion);
    }

    @Test
    void restoreBudgetStartsFreshAfterPromotionHasConsumedItsBudget() {
        var order = new ArrayList<String>();
        var deadlineSeen = new AtomicLong();
        var restoreStartedAt = new AtomicLong();
        preparePromotion(() -> {
            order.add("promotion");
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        });
        doAnswer(call -> {
            order.add("restores");
            restoreStartedAt.set(nanos.get());
            deadlineSeen.set(call.getArgument(0));
            return null;
        }).when(restores).recoverPending(anyLong());

        recovery().sweep();

        assertAll(
                () -> assertEquals(List.of("promotion", "restores"), order),
                () -> assertEquals(TimeUnit.SECONDS.toNanos(2), restoreStartedAt.get()),
                () -> assertEquals(restoreStartedAt.get() + TimeUnit.SECONDS.toNanos(2), deadlineSeen.get(),
                        "Restores need a new two-second allowance, not the expired promotion deadline"));
    }

    private void preparePromotion(Runnable recovered) {
        when(leases.pendingPromotionRuns()).thenReturn(List.of(promotion.getId()));
        when(runs.findById(promotion.getId())).thenReturn(Optional.of(promotion));
        when(sandbox.reconcilePendingOperations(promotion.getProjectId())).thenReturn(true);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).recovering(any(), any());
        when(changes.recoverPromotion(promotion)).thenAnswer(call -> {
            recovered.run();
            return false;
        });
    }

    private AgentRunRecovery recovery() {
        return new AgentRunRecovery(leases, restores, new AgentProperties(), runs, mock(AgentJobQueue.class),
                mock(AgentEvents.class), mock(MessageRepository.class), mock(RealtimeService.class), sandbox,
                changes, mock(SiteBuildService.class)) {
            @Override
            protected long nanoTime() {
                return nanos.get();
            }
        };
    }
}
