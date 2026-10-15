package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.stack.*;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Matched production-runner/actual Chromium behavior comparison. Provider data is controlled, not live. */
@EnabledIfSystemProperty(named="codepal.browser.it",matches="true")
class AgentCallCountBrowserIT {
    AgentCallCountTest.Trial trial(boolean finish,boolean polish,boolean fail) {
        var runner=new AgentCallCountTest() {
            @Override ProjectValidationService validation(LocalProject workspace,ChangeSetService changes,ObjectMapper mapper,boolean failure) {
                return AgentCallCountBrowserIT.this.validation(workspace,changes,mapper,failure);
            }
        };
        return runner.trial(finish,polish,fail);
    }
    @Test void fewerCallsPreserveActualAdditionBehaviorAndSaveIdenticalSource() {
        var before=trial(false,true,false);var after=trial(true,false,false);
        assertEquals(com.code.pal.agent.domain.RunStatus.DONE,before.result().status());
        assertEquals(com.code.pal.agent.domain.RunStatus.DONE,after.result().status());
        assertEquals(before.code(),after.code());assertEquals(3,before.calls());assertEquals(1,after.calls());
    }
    ProjectValidationService validation(LocalProject workspace,ChangeSetService changes,ObjectMapper mapper,boolean fail) {
        if(fail)return new AgentCallCountTest().validation(workspace,changes,mapper,true);
        workspace.properties.getSandbox().setRuntimeCheck(true);var sandbox=spy(workspace.sandbox);
        var criteria=mock(RunBehaviorCriteria.class);
        when(criteria.current(any(com.code.pal.agent.tool.AgentContext.class))).thenReturn(new BehaviorCriteria(1,List.of(
                new BehaviorCriteria.BrowserAction("a",BehaviorCriteria.BrowserKind.FILL,"#a","7"),
                new BehaviorCriteria.BrowserAction("b",BehaviorCriteria.BrowserKind.FILL,"#b","8"),
                new BehaviorCriteria.BrowserAction("add",BehaviorCriteria.BrowserKind.CLICK,"#calc",null),
                new BehaviorCriteria.BrowserAction("sum",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#result","15")),List.of(),false,false));
        doAnswer(call->browse(workspace,call.getArgument(1),call.getArgument(2))).when(sandbox).browse(eq(workspace.projectId),anyString(),anyString());
        var stacks=new StackPacks(mapper);var dependencies=mock(DependencyService.class);
        var runtime=new RuntimeCheckService(changes,stacks,workspace.files,sandbox,dependencies,workspace.properties,mapper,BuildVariables.NONE);
        runtime.useBehaviorCriteria(criteria);
        var validation=new ProjectValidationService(changes,stacks,workspace.properties,workspace.files,sandbox,dependencies,runtime);validation.useBehaviorCriteria(criteria);return validation;
    }
    SandboxPort.CommandResult browse(LocalProject workspace,String root,String recipe) throws Exception {
        var mapper=new ObjectMapper();assertEquals(4,mapper.readTree(recipe).path("actions").size(),"Assigned arithmetic actions must actually reach Chromium");
        Path source=workspace.dir().resolve(root).normalize().toAbsolutePath();assertTrue(source.startsWith(workspace.dir().toAbsolutePath()));
        String site=command(List.of("wsl.exe","-e","wslpath","-u",source.toString()));
        String probe=command(List.of("wsl.exe","-e","wslpath","-u",Path.of("src/main/resources/sandbox/browser/probe.mjs").toAbsolutePath().toString()));
        String image=command(List.of("wsl.exe","-e","docker","image","inspect","codepal-browser:efeb16a78e4c","--format","{{.Id}}"));assertTrue(image.matches("sha256:[a-f0-9]{64}"));
        String owner="codepal-codex-call-count-"+UUID.randomUUID();
        try {
            String output=command(List.of("wsl.exe","-e","docker","run","--rm","--name",owner,"--label","com.codepal.codex.call-count="+owner,
                    "--pull","never","--network","none","--user","1000:1000","--cpus","1","--memory","512m","--cap-drop","ALL","--security-opt","no-new-privileges",
                    "--mount","type=bind,source="+site+",target=/site,readonly","--mount","type=bind,source="+probe+",target=/opt/codepal/probe.mjs,readonly",
                    "--entrypoint","node",image,"/opt/codepal/probe.mjs","/site","200",Base64.getEncoder().encodeToString(recipe.getBytes(StandardCharsets.UTF_8))));
            var evidence=mapper.readTree(output.lines().filter(line->line.startsWith("{")).reduce((first,last)->last).orElseThrow());
            assertTrue(evidence.path("loaded").asBoolean());assertEquals(4,evidence.path("acceptance").size());
            for(var action:evidence.path("acceptance"))assertTrue(action.path("passed").asBoolean(),action.toString());
            return new SandboxPort.CommandResult(true,0,output,"");
        }finally {
            String remaining=command(List.of("wsl.exe","-e","docker","ps","-aq","--filter","label=com.codepal.codex.call-count="+owner));
            if(!remaining.isBlank())command(List.of("wsl.exe","-e","docker","rm","-f",remaining));
            assertTrue(command(List.of("wsl.exe","-e","docker","ps","-aq","--filter","label=com.codepal.codex.call-count="+owner)).isBlank());
        }
    }
    String command(List<String> args) throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        try(var reader=Executors.newVirtualThreadPerTaskExecutor()) {
            var output=reader.submit(()->new String(process.getInputStream().readNBytes(65_537),StandardCharsets.UTF_8));
            if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Owned browser command timed out");}
            String text=output.get(5,TimeUnit.SECONDS);assertEquals(0,process.exitValue(),text);assertTrue(text.length()<=65_536);return text.strip();
        }
    }
}
