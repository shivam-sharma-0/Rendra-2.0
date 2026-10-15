package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.*;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentHeartbeatTest {
    @Test void lostRedisOwnerStopsWithoutFresheningHeartbeat() {
        var queue=mock(AgentJobQueue.class);var runs=mock(AgentRunRepository.class);
        var heart=new AgentHeartbeat(runs,queue,new AgentProperties(),mock(PlatformTransactionManager.class));
        var signal=new RunCancellation(UUID.randomUUID());
        try { heart.beat(signal.runId(),UUID.randomUUID(),null,signal);
            assertTrue(signal.isCancelled());verify(runs,never()).touchHeartbeat(any(),any());
        } finally { heart.shutdown(); }
    }
    @Test void failedDurableRenewalStopsEvenWhenRedisRenewed() {
        var queue=mock(AgentJobQueue.class);var runs=mock(AgentRunRepository.class);
        var leases=mock(ProjectLeaseService.class);
        var heart=new AgentHeartbeat(runs,queue,new AgentProperties(),mock(PlatformTransactionManager.class));heart.useLeases(leases);
        var fence=new RunFence(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2);
        var signal=new RunCancellation(fence.runId());when(queue.renewLock(fence.projectId(),fence.ownerId())).thenReturn(true);
        try { heart.beat(fence.runId(),fence.projectId(),fence,signal);
            assertTrue(signal.isCancelled());verify(leases).renew(fence);verify(runs,never()).touchHeartbeat(any(),any());
        } finally { heart.shutdown(); }
    }
}
