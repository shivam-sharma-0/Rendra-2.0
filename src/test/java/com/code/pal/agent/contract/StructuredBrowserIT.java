package com.code.pal.agent.contract;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.route.*;
import com.code.pal.agent.stack.*;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real production coordinator/validation/browser with fake owned model data; no provider request. */
@EnabledIfSystemProperty(named="codepal.browser.it",matches="true")
class StructuredBrowserIT {
    @Test void generatedStaticUnitMustPassRealBrowserBeforeCompletion() throws Exception {verify(false,false,false);}
    @Test void existingFeatureFailureRepairsOnlyItsUnitAndPreservesOtherFiles() throws Exception {verify(true,false,false);}
    @Test void independentTwoWorkerWavePassesActualBrowser() throws Exception {verify(false,true,false);}
    @Test void reusedOutputPassesActualBrowserWithoutModelSpend() throws Exception {verify(false,false,true);}
    private void verify(boolean repair,boolean parallel,boolean cached) throws Exception {
        var f=new ContractCoordinatorTest();f.props.getSandbox().setRuntimeCheck(true);
        var contract=parallel?BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",List.of()),BuildContractTest.unit("style","style.css",List.of()))):f.c;
        if(parallel){f.props.setParallelUnits(true);var s=f.props.getScheduling();s.setEnabled(true);var pool=new com.code.pal.agent.config.AgentProperties.SchedulingPool();pool.setAccount("fixture");pool.setConcurrency(2);s.getPools().put(f.props.preferredModel(ProjectType.BUILD).provider(),pool);}
        if(repair)f.project.write("index.html","<!doctype html><main>Existing app</main>").write("notes.txt","Keep existing feature");
        var sandbox=spy(f.project.sandbox);var owner="codepal-codex-structured-browser-"+UUID.randomUUID().toString().replace("-","");
        String image=command(List.of("wsl.exe","-e","docker","image","inspect","codepal-browser:efeb16a78e4c","--format","{{.Id}}"));
        assertTrue(image.matches("sha256:[a-f0-9]{64}"));
        doAnswer(call->browse(f,owner,image,call.getArgument(1),"{\"schema\":1,\"actions\":[]}")).when(sandbox).browse(eq(f.project.projectId),anyString());
        doAnswer(call->browse(f,owner,image,call.getArgument(1),call.getArgument(2))).when(sandbox).browse(eq(f.project.projectId),anyString(),anyString());
        var dependencies=mock(DependencyService.class);var stacks=new StackPacks(f.mapper);
        var browser=new RuntimeCheckService(f.changes,stacks,f.project.files,sandbox,dependencies,f.props,f.mapper,BuildVariables.NONE);
        browser.useBehaviorCriteria(f.criteria);when(f.criteria.current(any())).thenReturn(contract.acceptance());
        var validator=new ProjectValidationService(f.changes,stacks,f.props,f.project.files,sandbox,dependencies,browser);
        var coordinator=new ContractCoordinator(f.store,new ContractPlanner(f.mapper),new ContractScaffold(f.mapper),new UnitGenerator(f.mapper),f.memory,f.decisions,f.project.files,f.changes,validator,f.criteria,f.checkpoints,f.props,f.mapper);
        var run=f.run(ProjectType.BUILD);var ctx=f.context(run);when(f.store.forRun(f.project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),f.project.projectId,contract,"hash",f.binding,"APPROVED","BUILD_REQUEST"));
        if(cached){var cache=mock(UnitOutputCheckpoints.class);coordinator.useUnitCheckpoints(cache);when(cache.reuse(eq(run),any(),eq(ctx))).thenAnswer(call->{var in=(UnitGenerator.Input)call.getArgument(1);return new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html",in.snapshot().get("index.html").version(),"<!doctype html><main><output id='result'>5</output></main>")));});}
        var calls=new AtomicInteger();
        try {
            coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),f.props),"build the approved plan","fixture",request->{
                assertFalse(cached,"Recovered output must not call the model");
                if(parallel){calls.incrementAndGet();var in=f.mapper.readValue(request.messages().get(1).content(),UnitGenerator.Input.class);var path=in.unit().ownedPaths().getFirst();return new LlmClient.LlmResult(f.mapper.writeValueAsString(new UnitGenerator.Output(List.of(new UnitGenerator.Delta(path,in.snapshot().get(path).version(),path.endsWith("html")?"<!doctype html><main id='result'>5</main>":"body { color: black }")))),null,List.of(),new LlmClient.Usage(10,20),"stop");}
                var reply=f.generate(request);if(repair && calls.getAndIncrement()==0)return new LlmClient.LlmResult(reply.content().replace(">5<",">4<"),null,List.of(),reply.usage(),"stop");
                if(!repair)calls.incrementAndGet();return reply;
            },()->{},title->{});
            assertEquals(cached?0:parallel||repair?2:1,calls.get());assertFalse(f.changes.hasUnvalidatedChanges(ctx));
            assertEquals(com.code.pal.agent.plan.AgentPlanState.StepStatus.COMPLETED,ctx.plan().snapshot().steps().getFirst().status());
            if(repair){assertEquals("Keep existing feature",f.project.read("notes.txt"));assertEquals("<!doctype html><main>Existing app</main>",f.project.read("index.html"));}
            else assertFalse(f.project.exists("index.html"));
        }finally {
            assertEquals("",command(List.of("wsl.exe","-e","docker","ps","-aq","--filter","label=com.codepal.codex.structured-browser="+owner)),"owned --rm browser containers must be gone");
        }
    }
    private SandboxPort.CommandResult browse(ContractCoordinatorTest f,String owner,String image,String root,String recipe) throws Exception {
        Path source=f.project.dir().resolve(root).normalize().toAbsolutePath();assertTrue(source.startsWith(f.project.dir().toAbsolutePath()));
        String site=command(List.of("wsl.exe","-e","wslpath","-u",source.toString()));
        String probe=command(List.of("wsl.exe","-e","wslpath","-u",Path.of("src/main/resources/sandbox/browser/probe.mjs").toAbsolutePath().toString()));
        String encoded=Base64.getEncoder().encodeToString(recipe.getBytes(StandardCharsets.UTF_8));
        String result=command(List.of("wsl.exe","-e","docker","run","--rm","--name",owner+"-"+UUID.randomUUID(),"--label","com.codepal.codex.structured-browser="+owner,
                "--pull","never","--network","none","--user","1000:1000","--cpus","1","--memory","512m","--cap-drop","ALL","--security-opt","no-new-privileges",
                "--mount","type=bind,source="+site+",target=/site,readonly","--mount","type=bind,source="+probe+",target=/opt/codepal/probe.mjs,readonly","--entrypoint","node",image,"/opt/codepal/probe.mjs","/site","200",encoded));
        return new SandboxPort.CommandResult(true,0,result,"");
    }
    private String command(List<String> args) throws Exception {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        try(var reader=Executors.newVirtualThreadPerTaskExecutor()){
            var reading=reader.submit(()->new String(p.getInputStream().readNBytes(65537),StandardCharsets.UTF_8));
            if(!p.waitFor(45,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("Disposable browser command timed out");}
            String output=reading.get(5,TimeUnit.SECONDS);assertEquals(0,p.exitValue(),output);assertTrue(output.length()<=65536);return output.strip();
        }
    }
}
