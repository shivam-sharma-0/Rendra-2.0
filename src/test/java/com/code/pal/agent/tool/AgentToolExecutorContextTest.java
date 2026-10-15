package com.code.pal.agent.tool;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentToolExecutorContextTest {
    @Test void parallelToolsCarryOwnerAndStopWithoutWaitingForTheRunDeadline() throws Exception {
        var executor=new AgentToolExecutor(new AgentProperties(),new ToolRegistry(List.of()),mock(ProjectService.class));executor.start();
        var signal=new RunCancellation(UUID.randomUUID());
        var owner=new LlmCallContext(7L,UUID.randomUUID(),signal.runId(),CallPurpose.AGENT,true);
        var fence=new com.code.pal.agent.lease.RunFence(owner.projectId(),owner.runId(),UUID.randomUUID(),2);
        var entered=new CountDownLatch(2);
        Callable<ToolExecutionResult> task=()-> {
            assertEquals(owner,LlmCallContext.current());assertSame(signal,RunCancellation.current());entered.countDown();
            assertEquals(fence,com.code.pal.agent.lease.RunFence.current());
            while(!signal.isCancelled()) Thread.sleep(10);
            return ToolExecutionResult.success(Map.of("stopped",true));
        };
        try(var caller=Executors.newSingleThreadExecutor()) {
            var future=caller.submit(()->{try(var stopped=RunCancellation.bind(signal);var context=LlmCallContext.bind(owner);var generation=com.code.pal.agent.lease.RunFence.bind(fence)) {
                return executor.executeParallel(List.of(task,task),System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
            }});
            assertTrue(entered.await(3,TimeUnit.SECONDS));signal.cancel();
            var failed=assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));
            assertInstanceOf(LlmCancelledException.class,failed.getCause());
        } finally { signal.cancel();executor.stop(); }
    }
}
