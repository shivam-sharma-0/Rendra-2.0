package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentHeartbeatTest {
    @Test
    void missingFenceStopsWithoutRenewingRedis() {
        var queue = mock(AgentJobQueue.class);
        var heart = new AgentHeartbeat(queue, new AgentProperties(), mock(ProjectLeaseService.class));
        var signal = new RunCancellation(UUID.randomUUID());
        try {
            heart.beat(signal.runId(), UUID.randomUUID(), null, signal);
            assertTrue(signal.isOwnershipLost());
            verifyNoInteractions(queue);
        } finally {
            heart.shutdown();
        }
    }
    @Test
    void failedDurableRenewalStopsBeforeRedisWork() {
        var queue = mock(AgentJobQueue.class);
        var leases = mock(ProjectLeaseService.class);
        var heart = new AgentHeartbeat(queue, new AgentProperties(), leases);
        var fence = new RunFence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
        var signal = new RunCancellation(fence.runId());
        try {
            heart.beat(fence.runId(), fence.projectId(), fence, signal);
            assertTrue(signal.isOwnershipLost());
            verify(leases).renew(fence);
            verifyNoInteractions(queue);
        } finally {
            heart.shutdown();
        }
    }

    @Test
    void redisFailureDoesNotCancelAValidDatabaseOwner() {
        var queue = mock(AgentJobQueue.class);
        var leases = mock(ProjectLeaseService.class);
        var heartbeat = new AgentHeartbeat(queue, new AgentProperties(), leases);
        var fence = new RunFence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
        var signal = new RunCancellation(fence.runId());
        when(leases.renew(fence)).thenReturn(true);
        when(queue.renewLock(fence.projectId(), fence.ownerId())).thenThrow(new IllegalStateException("Redis down"));
        try {
            heartbeat.beat(fence.runId(), fence.projectId(), fence, signal);
            assertFalse(signal.isCancelled());
            verify(leases).renew(fence);
        } finally {
            heartbeat.shutdown();
        }
    }

    @Test
    void missingRedisLockIsReacquiredWithoutCancellingTheDatabaseOwner() {
        var queue = mock(AgentJobQueue.class);
        var leases = mock(ProjectLeaseService.class);
        var heartbeat = new AgentHeartbeat(queue, new AgentProperties(), leases);
        var fence = new RunFence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
        var signal = new RunCancellation(fence.runId());
        when(leases.renew(fence)).thenReturn(true);
        when(queue.tryLock(fence.projectId(), fence.ownerId())).thenReturn(true);
        try {
            heartbeat.beat(fence.runId(), fence.projectId(), fence, signal);
            assertFalse(signal.isCancelled());
            verify(queue).tryLock(fence.projectId(), fence.ownerId());
        } finally {
            heartbeat.shutdown();
        }
    }
}
