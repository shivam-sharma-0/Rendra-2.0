package com.code.pal.agent;

import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentCancellationsTest {
    @Test void durableStopReachesAnotherWorkerWhileRedisIsUnavailable() {
        var queue=mock(AgentJobQueue.class);var runs=mock(com.code.pal.agent.repository.AgentRunRepository.class);var id=UUID.randomUUID();
        var run=com.code.pal.agent.domain.AgentRun.builder().id(id).build();
        when(runs.findById(id)).thenReturn(java.util.Optional.of(run));
        var registry=new AgentCancellations(queue,runs);
        try {
            var signal=registry.register(id);assertFalse(signal.isCancelled());
            run.setCancelRequestedAt(java.time.Instant.now());
            when(queue.isCancelRequested(id)).thenThrow(new IllegalStateException("Redis unavailable"));
            registry.pollOnce();assertTrue(signal.isCancelled());
        } finally { registry.shutdown(); }
    }

    @Test
    void aStopRequestedWhileQueuedCancelsAtRegistration() {
        AgentJobQueue queue = mock(AgentJobQueue.class);
        UUID run = UUID.randomUUID();
        when(queue.isCancelRequested(run)).thenReturn(true);
        AgentCancellations cancellations = new AgentCancellations(queue);
        assertTrue(cancellations.register(run).isCancelled());
        cancellations.shutdown();
    }

    @Test
    void localCancelIsImmediateAndPollingCatchesOtherInstances() {
        AgentJobQueue queue = mock(AgentJobQueue.class);
        UUID local = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        AgentCancellations cancellations = new AgentCancellations(queue);
        RunCancellation a = cancellations.register(local);
        RunCancellation b = cancellations.register(remote);

        assertTrue(cancellations.cancelLocal(local));
        assertTrue(a.isCancelled());
        assertFalse(b.isCancelled());

        when(queue.isCancelRequested(remote)).thenReturn(true);
        cancellations.pollOnce();
        assertTrue(b.isCancelled());

        cancellations.unregister(local);
        assertFalse(cancellations.cancelLocal(local));
        cancellations.shutdown();
    }
}
