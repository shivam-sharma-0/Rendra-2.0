package com.code.pal.agent;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.*;
import com.code.pal.agent.tool.impl.*;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.*;
import com.code.pal.project.service.ProjectService;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRepairStopTest {
    @Test void exhaustedValidationDoesNotSendADiscardedFinalModelCall() { exercise(true); }
    @Test void repeatedPatchMissesStopBeforeAnotherModelCallOrCommit() { exercise(false); }
    @Test void oversizedInitialContextDoesNotReachTheProvider() { exercise(false,true); }
    private void exercise(boolean terminalValidation) {exercise(terminalValidation,false);}
    private void exercise(boolean terminalValidation,boolean tinyContext) {
        var props = new AgentProperties(); props.setPolishTurn(false); props.setMaxPatchMisses(2);
        props.setMaxIterations(12); props.setMaxModelCalls(12); props.getModels().setBuild(List.of("test"));
        if(tinyContext)props.setContextBudgetChars(1);
        var workspace = LocalProject.create(props); workspace.write("index.html", "accepted");
        var mapper = new ObjectMapper(); var runs = mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        var rights = mock(ProjectService.class);
        var project = Project.builder().id(workspace.projectId).workspaceId(UUID.randomUUID()).ownerId(7L).name("App")
                .type(ProjectType.BUILD).deploymentStatus(DeploymentStatus.NONE).lastActivityAt(Instant.now()).build();
        when(rights.requireManageable(7L,workspace.projectId)).thenReturn(project);
        when(rights.requireAccessible(7L,workspace.projectId)).thenReturn(project);
        when(rights.requireCanEdit(7L,workspace.projectId)).thenReturn(project);
        var changes = new ChangeSetService(mock(FileChangeRepository.class),runs,mapper,props,workspace.files,workspace.sandbox,rights);
        var validation = mock(ProjectValidationService.class);
        AgentTool failingValidation = new AgentTool() {
            public String name() { return "validateChanges"; } public String description() { return "Validation fixture"; }
            public boolean mutating() { return true; } public Map<String,Object> parametersSchema() { return Map.of(); }
            public Object execute(Map<String,Object> args,AgentContext ctx) { changes.recordTerminalValidationFailure(ctx,List.of()); return Map.of("passed",false); }
        };
        var registry = new ToolRegistry(List.of(new SetPlanTool(),new ReadFileTool(),new PatchFileTool(changes),new CreateFileTool(changes),failingValidation));
        var executor = new AgentToolExecutor(props,registry,rights); var calls = new AtomicInteger();
        var run = AgentRun.builder().id(UUID.randomUUID()).projectId(workspace.projectId).userId(7L).mode(ProjectType.BUILD).messageId("repair-stop").status(RunStatus.QUEUED).build();
        LlmClient model = new LlmClient() {
            public LlmResult chatStreaming(LlmRequest request,java.util.function.Consumer<String> delta) {
                int n = calls.incrementAndGet();
                String name,arguments;
                if(n==1) {name="setPlan";arguments="{\"steps\":[\"Edit\",\"Validate\"]}";}
                else if(terminalValidation) {
                    if(n==2) { name="createFile";arguments="{\"path\":\"new.txt\",\"content\":\"candidate\"}"; }
                    else if(n==3) {name="validateChanges";arguments="{}";}
                    else throw new AssertionError("Discarded final model call must not be sent");
                } else {
                    if(n>4) throw new AssertionError("No model call after two misses");
                    if(n==3) {name="readFile";arguments="{\"path\":\"index.html\"}";}
                    else {name="patchFile";arguments="{\"path\":\"index.html\",\"expectedVersion\":\""+FileVersions.of(workspace.path("index.html"))+"\",\"oldText\":\"missing\",\"newText\":\"x\"}";}
                }
                return new LlmResult("",null,List.of(new ToolCall("t"+n,name,arguments)),new Usage(1,1),"tool_calls");
            }
        };
        var messages = mock(MessageRepository.class);
        com.code.pal.testsupport.QueuedRequestFixture.stubOriginal(messages,run,Message.user(workspace.projectId,"repair-user","Update the application",ProjectType.BUILD,7L));
        var heartbeat = mock(AgentHeartbeat.class); when(heartbeat.start(run.getId(),workspace.projectId)).thenReturn(()->{});
        var runner = new AgentRunner(model,registry,executor,mock(AgentEvents.class),props,new ProjectContextService(props,mapper),
                runs,rights,messages,mock(com.code.pal.realtime.RealtimeService.class),workspace.files,heartbeat,mock(AgentJobQueue.class),mapper,changes,validation,mock(com.code.pal.notification.service.NotificationService.class));
        var result = runner.run(run);
        assertEquals(RunStatus.FAILED,result.status());
        assertEquals(tinyContext ? 0 : terminalValidation ? 3 : 4,calls.get());
        assertEquals(calls.get(),result.modelCalls());
        assertEquals("accepted",workspace.read("index.html"));
        assertFalse(workspace.exists("new.txt"));
        verify(runs,never()).beginCommit(any(),any(),any());
    }
}
