package com.code.pal.agent;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.intent.IntentClassifier;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.impl.*;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentSelfRepairTest {
    @Test
    void repairsAnIntroducedTypeErrorAndCommitsOnlyTheValidatedRevision() throws Exception {
        AgentProperties props = new AgentProperties();
        LocalProject workspace = LocalProject.create(props);
        UUID projectId = workspace.projectId; UUID workspaceId = UUID.randomUUID(); UUID runId = UUID.randomUUID();
        Path live = workspace.dir();
        workspace.write("index.html", "<div id=\"root\"></div>");
        workspace.write("src/App.tsx", "export const theme = 'OK';\n");
        workspace.write("package.json", "{\"packageManager\":\"npm@10.9.0\",\"scripts\":{\"typecheck\":\"node validate.cjs\"}}");
        workspace.write("package-lock.json", "{\"lockfileVersion\":3}");
        workspace.write("validate.cjs", """
                const fs=require('fs');const value=fs.readFileSync('src/App.tsx','utf8');
                if(value.includes('BROKEN')){console.error('src/App.tsx(1,22): error TS2307: Cannot find module theme');process.exit(1);}
                """);
        String initialVersion = FileVersions.of(live.resolve("src/App.tsx"));
        Path bad = Files.createTempFile(Path.of("target"), "bad-", ".tsx"); Files.writeString(bad, "export const theme = 'BROKEN';\n");
        String badVersion = FileVersions.of(bad);

        props.setMaxIterations(12); props.setMaxModelCalls(12); props.setMaxToolCalls(20);
        props.setRunTimeoutMs(90_000); props.getSandbox().setCommandTimeoutMs(15_000); props.setMaxRepairAttempts(3);
        props.getModels().setBuild(List.of("test-model"));
        ObjectMapper mapper = new ObjectMapper();
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        FileChangeRepository fileChanges = mock(FileChangeRepository.class);
        when(fileChanges.save(any())).thenAnswer(call -> call.getArgument(0));
        ChangeSetService changeSets = new ChangeSetService(fileChanges, runs, mapper, props, workspace.files, workspace.sandbox);
        ProjectValidationService validation = new ProjectValidationService(changeSets, new StackPacks(mapper), props,
                workspace.files, workspace.sandbox,
                new com.code.pal.agent.stack.DependencyService(new StackPacks(mapper), workspace.files, workspace.sandbox, props), null);

        ToolRegistry registry = new ToolRegistry(List.of(new ReadFileTool(), new SetPlanTool(),
                new PatchFileTool(changeSets), new RunTypeCheckTool(validation), new ValidateChangesTool(validation)));
        ProjectService projects = mock(ProjectService.class);
        Project project = Project.builder().id(projectId).workspaceId(workspaceId).ownerId(7L).name("React app")
                .type(ProjectType.BUILD).isPublic(false).deploymentStatus(DeploymentStatus.NONE)
                .lastActivityAt(Instant.now()).build();
        when(projects.requireManageable(7L, projectId)).thenReturn(project);
        when(projects.requireAccessible(7L, projectId)).thenReturn(project);
        AgentToolExecutor executor = new AgentToolExecutor(props, registry, projects);

        AgentRun run = AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.BUILD)
                .messageId("assistant-repair").status(RunStatus.QUEUED).build();
        MessageRepository messages = mock(MessageRepository.class);
        Message request = Message.user(projectId, "user-repair", "Update the theme constant safely.", ProjectType.BUILD, 7L);
        when(messages.findByProjectId(eq(projectId), any())).thenReturn(new PageImpl<>(List.of(request)));
        when(messages.findById("assistant-repair")).thenReturn(Optional.empty());
        AgentHeartbeat heartbeat = mock(AgentHeartbeat.class); when(heartbeat.start(runId, projectId)).thenReturn(() -> {});
        AgentJobQueue queue = mock(AgentJobQueue.class);
        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        when(intentClassifier.classify(any())).thenReturn(AgentIntent.TASK);

        AgentRunner runner = new AgentRunner(new RepairingModel(initialVersion, badVersion), intentClassifier, registry, executor,
                mock(AgentEvents.class), props, new ProjectContextService(props, mapper), runs, projects, messages,
                mock(RealtimeService.class), workspace.files, heartbeat, queue, mapper, changeSets, validation,
                mock(NotificationService.class));

        AgentRunResult result = runner.run(run);

        assertEquals(RunStatus.DONE, result.status());
        assertEquals("export const theme = 'DARK';\n", Files.readString(live.resolve("src/App.tsx")));
        assertTrue(result.response().contains("src/App.tsx"));
        verify(fileChanges, times(2)).save(any(FileChange.class));
    }

    private static final class RepairingModel implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger(); private final String initial; private final String broken;
        private RepairingModel(String initial, String broken) { this.initial=initial; this.broken=broken; }
        @Override public LlmResult chatStreaming(LlmRequest request, java.util.function.Consumer<String> delta) {
            return switch (calls.incrementAndGet()) {
                case 1 -> tool("plan", "setPlan", "{\"steps\":[\"Inspect theme\",\"Modify theme\",\"Type-check\",\"Repair diagnostics\"]}");
                case 2 -> tool("read-1", "readFile", "{\"path\":\"src/App.tsx\"}");
                case 3 -> tool("patch-bad", "patchFile", "{\"path\":\"src/App.tsx\",\"expectedVersion\":\""+initial+"\",\"oldText\":\"OK\",\"newText\":\"BROKEN\"}");
                case 4 -> tool("check-1", "runTypeCheck", "{}");
                case 5 -> {
                    assertTrue(request.messages().getLast().content().contains("TS2307"));
                    yield tool("read-2", "readFile", "{\"path\":\"src/App.tsx\"}");
                }
                case 6 -> tool("patch-fix", "patchFile", "{\"path\":\"src/App.tsx\",\"expectedVersion\":\""+broken+"\",\"oldText\":\"BROKEN\",\"newText\":\"DARK\"}");
                case 7 -> tool("validate", "validateChanges", "{}");
                default -> {
                    String answer="Updated and validated src/App.tsx."; delta.accept(answer);
                    yield new LlmResult(answer,null,List.of(),new Usage(1,1),"stop");
                }
            };
        }
        private LlmResult tool(String id,String name,String arguments){return new LlmResult("",null,List.of(new ToolCall(id,name,arguments)),new Usage(1,1),"tool_calls");}
    }
}
