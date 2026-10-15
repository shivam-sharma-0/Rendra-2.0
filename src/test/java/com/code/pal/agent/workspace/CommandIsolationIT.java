package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real adapter; only a runner-owned path under target and a pinned cached image. */
@EnabledIfSystemProperty(named="codepal.command.it",matches="true")
class CommandIsolationIT {
    @Test void browserExercisesCrudAndReloadThroughCandidateRelayWhileNetworkIsNone() throws Exception {
        var mapper=new tools.jackson.databind.ObjectMapper();var title=new java.util.concurrent.atomic.AtomicReference<String>();
        var completed=new java.util.concurrent.atomic.AtomicBoolean();
        var api=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        api.createContext("/api/todos",request->{
            String method=request.getRequestMethod();
            if(method.equals("POST") || method.equals("PUT")){var body=mapper.readTree(request.getRequestBody().readAllBytes());if(body.has("title"))title.set(body.path("title").asString());if(body.has("completed"))completed.set(body.path("completed").asBoolean());}
            if(method.equals("DELETE"))title.set(null);
            byte[] reply=mapper.writeValueAsBytes(title.get()==null?List.of():List.of(Map.of("title",title.get(),"completed",completed.get())));
            request.getResponseHeaders().set("content-type","application/json");request.sendResponseHeaders(200,reply.length);request.getResponseBody().write(reply);request.close();
        });api.start();
        try {
            String html="""
                <!doctype html><h1>Tasks</h1><input id="title"><button id="add">Add</button><button id="edit">Edit</button><button id="toggle">Complete</button><button id="delete">Delete</button><ul id="items"></ul><output id="status"></output>
                <script>
                async function call(method,body){const response=await fetch('/api/todos',{method,headers:{'content-type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});if(!response.ok)throw Error('API failed');const rows=await response.json();document.querySelector('#items').replaceChildren(...rows.map(row=>{const el=document.createElement('li');el.textContent=row.title;return el}));document.querySelector('#status').textContent=rows[0]?.completed?'Completed':'Open'}
                document.querySelector('#add').onclick=()=>call('POST',{title:document.querySelector('#title').value});document.querySelector('#edit').onclick=()=>call('PUT',{title:document.querySelector('#title').value});document.querySelector('#toggle').onclick=()=>call('PUT',{completed:true});document.querySelector('#delete').onclick=()=>call('DELETE');call('GET');
                </script>
                """;
            Files.writeString(Path.of(props.getWorkspaceBasePath(),project.toString(),candidate,"index.html"),html);
            var actions=List.of(
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("title",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.FILL,"#title","First"),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("add",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.CLICK,"#add",null),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("created",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#items","First"),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("reload",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.RELOAD,null,null),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("persisted",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#items","First"),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("newTitle",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.FILL,"#title","Updated"),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("edit",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.CLICK,"#edit",null),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("edited",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#items","Updated"),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("toggle",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.CLICK,"#toggle",null),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("completed",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#status","Completed"),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("delete",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.CLICK,"#delete",null),
                new com.code.pal.agent.validation.BehaviorCriteria.BrowserAction("empty",com.code.pal.agent.validation.BehaviorCriteria.BrowserKind.ASSERT_COUNT,"#items li","0"));
            var result=sandbox.browseWithApi(project,candidate,mapper.writeValueAsString(Map.of("schema",1,"actions",actions)),new SandboxPort.ServerEndpoint("http://127.0.0.1:"+api.getAddress().getPort(),Map.of()));
            result.requireSuccess();var report=mapper.readTree(result.stdout().lines().filter(l->l.startsWith("{")).reduce((a,b)->b).orElseThrow());
            assertTrue(report.path("issues").isEmpty(),report.toString());assertEquals(actions.size(),report.path("acceptance").size());
            for(var step:report.path("acceptance"))assertTrue(step.path("passed").asBoolean(),step.toString());
            assertNull(title.get());assertTrue(completed.get());
        }finally{api.stop(0);}
    }
    private AgentProperties props;
    private DockerProjectEnvironment sandbox;
    private UUID project;
    private Path evidence;
    private final String candidate=".codepal/changesets/candidate/workspace";
    @BeforeEach void open() throws Exception {
        String id=System.getProperty("codex.command.project");
        assertTrue(id!=null && id.matches("codepal-codex-command-it-[a-f0-9]{12}"));
        Path owned=Path.of("target/codex-command-it",id).toAbsolutePath().normalize();
        Path base=Path.of(System.getProperty("codex.command.base")).toAbsolutePath().normalize();
        assertEquals(owned.resolve("workspaces"),base);
        String image=System.getProperty("codex.command.image");assertTrue(image.matches("sha256:[a-f0-9]{64}"));
        evidence=owned.resolve("projects.txt");Files.createDirectories(owned);
        project=UUID.randomUUID();Files.writeString(evidence,project+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        props=new AgentProperties();props.setWorkspaceBasePath(base.toString());
        props.getSandbox().setHostWorkspacePath(System.getProperty("codex.command.host-base"));
        props.getSandbox().setContainerPrefix(id+"-");props.getSandbox().setImage(image);
        props.getSandbox().setDockerCommand("wsl.exe");props.getSandbox().setDockerCommandArgs("-e docker");
        props.getSandbox().setNetwork("none");props.getSandbox().setCommandTimeoutMs(10000);
        sandbox=new DockerProjectEnvironment(props);sandbox.verifyEnvironment();sandbox.ensure(project);
        Files.createDirectories(base.resolve(project.toString()).resolve(candidate));
    }
    @AfterEach void cleanup() { if(sandbox!=null && project!=null) sandbox.destroy(project); }
    private String docker(String... arguments) throws Exception {
        var args=new ArrayList<>(List.of("wsl.exe","-e","docker"));args.addAll(List.of(arguments));
        var process=new ProcessBuilder(args).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    private void awaitRunning(UUID run) throws Exception {
        Instant end=Instant.now().plusSeconds(10);
        while(Instant.now().isBefore(end)) {
            if(!docker("ps","-q","--filter","label=com.codepal.run="+run).isBlank()) return;
            Thread.sleep(100);
        }
        fail("Owned command did not start");
    }
    private void keepUnrelatedProcess() {
        sandbox.exec(project,List.of("sh","-c","node -e 'setInterval(()=>{},1000)' >/tmp/other.log 2>&1 & echo $! >/tmp/other.pid")).requireSuccess();
    }
    private void requireUnrelatedAlive() { sandbox.exec(project,List.of("sh","-c","kill -0 $(cat /tmp/other.pid)")).requireSuccess(); }
    @Test void stopTerminatesItsContainerAndKeepsUnrelatedProjectProcess() throws Exception {
        keepUnrelatedProcess();var signal=new RunCancellation(UUID.randomUUID());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()-> { try(var ignored=RunCancellation.bind(signal)) {
                return sandbox.exec(project,candidate,List.of("node","-e","setInterval(()=>{},1000)"));
            }});
            awaitRunning(signal.runId());long started=System.nanoTime();signal.cancel();
            assertTrue(future.get(10,TimeUnit.SECONDS).cancelled());
            long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertTrue(millis<5000,"Stop completion ms="+millis);requireUnrelatedAlive();
            assertEquals("",docker("ps","-aq","--filter","label=com.codepal.run="+signal.runId()));
            Files.writeString(evidence.getParent().resolve("latency.txt"),"cancelMs="+millis+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } finally { signal.cancel(); }
    }
    @Test void timeoutAlsoRemovesOnlyTheOwnedCommand() throws Exception {
        keepUnrelatedProcess();props.getSandbox().setCommandTimeoutMs(1000);
        var signal=new RunCancellation(UUID.randomUUID());
        try(var ignored=RunCancellation.bind(signal)) {
            assertTrue(sandbox.exec(project,candidate,List.of("node","-e","setInterval(()=>{},1000)")).timedOut());
        }
        requireUnrelatedAlive();assertEquals("",docker("ps","-aq","--filter","label=com.codepal.run="+signal.runId()));
    }
    @Test void exitStatusAndLiteralArgumentsSurviveOwnedExecution() throws Exception {
        try(var ignored=RunCancellation.bind(new RunCancellation(UUID.randomUUID()))) {
            var result=sandbox.exec(project,candidate,List.of("node","-e","console.log(process.argv[1]);process.exit(7)","--","--rm"));
            assertEquals(7,result.exitCode());assertFalse(result.success());assertEquals("--rm",result.stdout().trim());
        }
    }
    @Test void candidateCannotReadSecretsHistoryLiveCodeOrWriteAcceptedFiles() throws Exception {
        Path base=Path.of(System.getProperty("codex.command.base")).resolve(project.toString());
        Files.createDirectories(base.resolve(".secrets"));Files.writeString(base.resolve(".secrets/key"),"fixture private");
        Files.createDirectories(base.resolve(".codepal/history"));Files.writeString(base.resolve(".codepal/history/accepted"),"history");
        Files.writeString(base.resolve("accepted.txt"),"original");
        try(var signal=RunCancellation.bind(new RunCancellation(UUID.randomUUID()))) {
            var result=sandbox.exec(project,candidate,List.of("node","-e",
                    "const fs=require('fs');for(const p of ['/workspace/accepted.txt','/workspace/.secrets/key','/workspace/.codepal/history/accepted']) { if(fs.existsSync(p)) process.exit(11);try {fs.writeFileSync(p,'unsafe');process.exit(12);}catch(e){}}fs.writeFileSync('candidate.txt','allowed');"));
            result.requireSuccess();
        }
        assertEquals("original",Files.readString(base.resolve("accepted.txt")));
        assertEquals("allowed",Files.readString(base.resolve(candidate).resolve("candidate.txt")));
    }
    @Test void aFailedArchiveProducerCannotBeHiddenByASuccessfulTarConsumer() {
        assertThrows(IllegalStateException.class,()->sandbox.copyTree(project,"missing-source",".codepal/incomplete-copy"));
    }
    @Test void actualViteBuildUsesWritableTemporaryCacheWithoutChangingInstalledDependencies() throws Exception {
        Path base=Path.of(System.getProperty("codex.command.base")).resolve(project.toString());var fixture=Path.of("target/codex-structured-app-fixtures/vite");assertTrue(Files.isDirectory(fixture));
        try(var paths=Files.walk(fixture)){for(Path file:paths.filter(Files::isRegularFile).toList()){var target=base.resolve(candidate).resolve(fixture.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);}}
        props.getSandbox().setInstallTimeoutMs(120000);props.getSandbox().setCommandTimeoutMs(120000);props.getSandbox().setInstallNetwork("bridge");
        var files=new HostProjectFiles(new WorkspaceStorage(props));var deps=new com.code.pal.agent.stack.DependencyService(new com.code.pal.agent.stack.StackPacks(new tools.jackson.databind.ObjectMapper()),files,sandbox,props);
        var prepared=deps.prepare(project,candidate);assertFalse(prepared.failed(),prepared.error());
        var result=sandbox.exec(project,candidate,List.of("npm","run","build"));assertTrue(result.success(),result.stdout());assertTrue(Files.isRegularFile(base.resolve(candidate).resolve("dist/index.html")));
        try(var sets=Files.list(base.resolve(".codepal/deps"))){for(Path set:sets.filter(Files::isDirectory).toList()){assertTrue(Files.isRegularFile(set.resolve("node_modules/vite/package.json")));try(var temp=Files.list(set.resolve("node_modules/.vite-temp"))){assertEquals(0,temp.count(),"command-local cache must not persist into shared dependencies");}}}
    }
    @Test void trustedTreeCopyReleasesItsIoRecordOnlyAfterOwnedContainerCleanup() throws Exception {
        Path base=Path.of(System.getProperty("codex.command.base")).resolve(project.toString());
        Files.writeString(base.resolve("accepted.txt"),"original");
        var io=org.mockito.Mockito.mock(com.code.pal.agent.lease.TrustedProjectIo.class);UUID operation=UUID.randomUUID();sandbox.useTrustedIo(io);
        org.mockito.Mockito.when(io.begin(org.mockito.ArgumentMatchers.eq(project),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(operation);
        sandbox.copyTree(project,".",".codepal/copied");
        assertEquals("original",Files.readString(base.resolve(".codepal/copied/accepted.txt")));
        org.mockito.Mockito.verify(io).finish(operation);
        assertEquals("",docker("ps","-aq","--filter","label=com.codepal.trusted=true","--filter","label=com.codepal.project="+project));
    }
}
