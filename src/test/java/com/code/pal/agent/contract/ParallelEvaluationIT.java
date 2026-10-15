package com.code.pal.agent.contract;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.route.*;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Matched controlled coordinator evaluation; fake provider delay/usage and validation, never vendor evidence. */
@EnabledIfSystemProperty(named="codepal.parallel.eval",matches="true")
class ParallelEvaluationIT {
    record Trial(boolean dependent,int width,long elapsedMs,int calls,int inputBytes,int reportedInput,int reportedOutput,int peak,Map<String,String> files){}
    Trial trial(boolean dependent,int width){
        var f=new ContractCoordinatorTest();var c=BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",dependent?List.of("style"):List.of()),BuildContractTest.unit("style","style.css",List.of())));
        f.props.setParallelUnits(width==2);var s=f.props.getScheduling();s.setEnabled(true);var pool=new AgentProperties.SchedulingPool();pool.setAccount("fixture");pool.setConcurrency(2);s.getPools().put(f.props.preferredModel(ProjectType.BUILD).provider(),pool);
        var run=f.run(ProjectType.BUILD);var ctx=f.context(run);when(f.store.forRun(f.project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),f.project.projectId,c,"hash",f.binding,"APPROVED","BUILD_REQUEST"));
        var calls=new AtomicInteger();var bytes=new AtomicInteger();var active=new AtomicInteger();var peak=new AtomicInteger();long started=System.nanoTime();
        f.coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),f.props),"build","fixture",request->{calls.incrementAndGet();bytes.addAndGet(request.messages().get(1).content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try{Thread.sleep(150);}catch(InterruptedException stop){throw new LlmCancelledException();}finally{active.decrementAndGet();}
            var in=f.mapper.readValue(request.messages().get(1).content(),UnitGenerator.Input.class);var path=in.unit().ownedPaths().getFirst();return new LlmClient.LlmResult(f.mapper.writeValueAsString(new UnitGenerator.Output(List.of(new UnitGenerator.Delta(path,in.snapshot().get(path).version(),path.endsWith("html")?"<main id='result'>5</main>":"body { color: black }")))),null,List.of(),new LlmClient.Usage(10,20),"stop");
        },()->{},title->{});
        long elapsed=(System.nanoTime()-started)/1000000;var content=new TreeMap<String,String>();for(String p:List.of("index.html","style.css"))content.put(p,f.project.files.readText(f.project.projectId,ctx.root(),p));
        assertFalse(f.changes.hasUnvalidatedChanges(ctx));assertFalse(f.project.exists("index.html"));return new Trial(dependent,width,elapsed,calls.get(),bytes.get(),calls.get()*10,calls.get()*20,peak.get(),content);
    }
    @Test void matchedTwoWorkerTrialRetainsOutputsAndCallUsageAndDependentWavesStaySequential() throws Exception {
        // Warm up classloading independently; measured trials then alternate width ordering.
        trial(false,1);trial(false,2);var results=new ArrayList<Trial>();
        for(boolean dependent:List.of(false,true))for(int i=0;i<5;i++){var a=trial(dependent,i%2==0?1:2);var b=trial(dependent,i%2==0?2:1);results.add(a);results.add(b);assertEquals(a.files(),b.files());assertEquals(a.calls(),b.calls());assertEquals(a.inputBytes(),b.inputBytes());assertEquals(a.reportedInput(),b.reportedInput());assertEquals(a.reportedOutput(),b.reportedOutput());assertEquals(2,a.calls());if(dependent){assertEquals(1,a.peak());assertEquals(1,b.peak());}}
        var summary=new LinkedHashMap<String,Object>();summary.put("provider","fake 150ms/unit; reported usage is synthetic; validation is mocked");summary.put("trials",results);
        for(boolean dependent:List.of(false,true)){var seq=results.stream().filter(t->t.dependent()==dependent&&t.width()==1).mapToLong(Trial::elapsedMs).sorted().toArray();var par=results.stream().filter(t->t.dependent()==dependent&&t.width()==2).mapToLong(Trial::elapsedMs).sorted().toArray();summary.put(dependent?"dependent":"independent",Map.of("sequentialMedianMs",seq[2],"twoWorkerMedianMs",par[2],"latencyRatio",(double)par[2]/seq[2]));if(!dependent)assertEquals(2,results.stream().filter(t->!t.dependent()&&t.width()==2).mapToInt(Trial::peak).min().orElseThrow());}
        var path=Path.of("target/codex-parallel-evaluation.json");Files.createDirectories(path.getParent());Files.writeString(path,new tools.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }
}
