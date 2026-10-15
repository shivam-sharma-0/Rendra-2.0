package com.code.pal.agent.contract;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class UnitWaveExecutorTest {
    final UnitWaveExecutor waves=new UnitWaveExecutor();
    UnitGenerator.Input input(String name){var c=BuildContractTest.valid(List.of(BuildContractTest.unit(name,name+".html",List.of())));return new UnitGenerator.Input(c,c.units().getFirst(),"request","approved",Map.of(name+".html",new UnitGenerator.FileView("absent",null)),"");}
    UnitGenerator.Output output(UnitGenerator.Input in){return new UnitGenerator.Output(List.of(new UnitGenerator.Delta(in.unit().ownedPaths().getFirst(),"absent",in.unit().id())));}
    @Test void twoWorkersOverlapOnFrozenDataAndRestoreIdentityWithOrderedBarrierResults() {
        var owner=new RunFence(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1);var caller=new LlmCallContext(7L,owner.projectId(),owner.runId(),CallPurpose.AGENT,true);var cancellation=new RunCancellation(owner.runId());var entered=new CountDownLatch(2);var peak=new AtomicInteger();var active=new AtomicInteger();
        try(var fence=RunFence.bind(owner);var context=LlmCallContext.bind(caller);var cancel=RunCancellation.bind(cancellation)){
            var result=waves.generate(List.of(input("first"),input("second")),2,2000,in->{
                assertEquals(owner,RunFence.current());assertEquals(caller,LlmCallContext.current());assertEquals(owner.runId(),RunCancellation.current().runId());
                peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();try{assertTrue(entered.await(1,TimeUnit.SECONDS));}catch(InterruptedException e){throw new LlmCancelledException();}finally{active.decrementAndGet();}return output(in);
            });
            assertEquals(2,peak.get());assertEquals(List.of("first","second"),result.stream().map(o->o.files().getFirst().content()).toList());assertFalse(cancellation.isCancelled());
        }
        assertNull(RunFence.current());assertEquals(LlmCallContext.UNKNOWN,LlmCallContext.current());
    }
    @Test void firstFailureCancelsSiblingAndReturnsNoPartialWave() throws Exception {
        var entered=new CountDownLatch(2);var stopped=new CountDownLatch(1);var failure=new IllegalStateException("owned failure");
        var thrown=assertThrows(IllegalStateException.class,()->waves.generate(List.of(input("first"),input("second")),2,2000,in->{
            boolean sibling=in.unit().id().equals("second");
            try {
                entered.countDown();
                try {assertTrue(entered.await(1,TimeUnit.SECONDS));}catch(InterruptedException cancelled){throw new LlmCancelledException();}
                if(!sibling)throw failure;
                while(!RunCancellation.current().isCancelled()){
                    try{Thread.sleep(5);}catch(InterruptedException cancelled){break;}
                }
                throw new LlmCancelledException();
            } finally {
                // Cancellation can interrupt the sibling while it is still leaving the entry barrier.
                if(sibling)stopped.countDown();
            }
        }));
        assertSame(failure,thrown);assertTrue(stopped.await(1,TimeUnit.SECONDS));
    }
    @Test void userStopReachesEveryWorkerAndLateOutputsAreDiscarded() throws Exception {
        var parent=new RunCancellation(UUID.randomUUID());var entered=new CountDownLatch(2);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var work=pool.submit(()->{try(var bound=RunCancellation.bind(parent)){return waves.generate(List.of(input("first"),input("second")),2,2000,in->{entered.countDown();while(!RunCancellation.current().isCancelled()){try{Thread.sleep(5);}catch(InterruptedException stop){break;}}return output(in);});}});
            assertTrue(entered.await(1,TimeUnit.SECONDS));parent.cancel();assertThrows(ExecutionException.class,()->work.get(1,TimeUnit.SECONDS));
        }
    }
    @Test void widthThreeIsNotAnAutomaticRolloutAndSequentialOutputIsIdentical() {
        var inputs=List.of(input("first"),input("second"));assertThrows(IllegalArgumentException.class,()->waves.generate(inputs,3,2000,this::output));
        assertEquals(waves.generate(inputs,1,2000,this::output),waves.generate(inputs,2,2000,this::output));
    }
}
