package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.preview.FrontendArtifactCache;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeArtifactReuseTest {
    private static final String COMPILER = ".codepal/compiler", RUNTIME = ".codepal/runtime";
    private final AgentProperties properties = new AgentProperties();
    private final LocalProject project = LocalProject.create(properties);
    private final AgentContext context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, ".", project.files);
    private final SandboxPort sandbox = spy(project.sandbox);
    private final StackPacks stacks = new StackPacks(new ObjectMapper());
    private final FrontendArtifactCache cache = new FrontendArtifactCache(properties, project.files, sandbox);
    private final RuntimeCheckService runtime;

    RuntimeArtifactReuseTest() {
        properties.getSandbox().setRequired(true);
        properties.getBuildArtifacts().setEnabled(true);
        for (String root : new String[]{COMPILER, RUNTIME}) {
            Starter.VITE_REACT.files(null).forEach((path, body) -> project.write(root + "/" + path, body));
            project.write(root + "/src/App.tsx", "export default function App(){return <main>Fixture</main>}");
        }
        project.write(COMPILER + "/dist/index.html", "<main>Compiled fixture</main>");
        var stack = stacks.detect(project.files, project.projectId, COMPILER);
        var build = stack.diagnosticBuild(project.files, project.projectId, COMPILER);
        assertTrue(cache.capture(cache.token(context, COMPILER, stack, build), context, COMPILER, stack, build));
        var changes = mock(ChangeSetService.class);
        when(changes.createValidationCandidate(context, "runtime")).thenReturn(RUNTIME);
        var dependencies = mock(DependencyService.class);
        when(dependencies.prepare(project.projectId, RUNTIME)).thenReturn(new DependencyService.Preparation(true, false, null));
        runtime = new RuntimeCheckService(changes, stacks, project.files, sandbox, dependencies, properties, new ObjectMapper(), BuildVariables.NONE);
        runtime.useArtifactCache(cache);
        doReturn(new SandboxPort.CommandResult(true, 0, "{\"loaded\":true,\"issues\":[],\"interactions\":[]}", ""))
                .when(sandbox).browse(project.projectId, RUNTIME + "/dist");
    }

    @Test
    void matchingCompilerOutputSkipsOnlyTheDuplicateBuildAndStillBrowses() {
        assertEquals(ValidationCheck.Outcome.PASS, runtime.evaluate(context).check().outcome());
        verify(sandbox, never()).exec(any(), anyString(), anyList());
        verify(sandbox).browse(project.projectId, RUNTIME + "/dist");
        verify(sandbox).removeTree(project.projectId, RUNTIME);
    }

    @Test
    void aChangedCandidateCannotUseAnEarlierCompilerArtifact() {
        project.write(RUNTIME + "/src/App.tsx", "Changed source");
        doReturn(new SandboxPort.CommandResult(true, 1, "Fixture compiler failure", ""))
                .when(sandbox).exec(eq(project.projectId), eq(RUNTIME), anyList());
        assertTrue(runtime.evaluate(context).check().unavailable());
        verify(sandbox).exec(eq(project.projectId), eq(RUNTIME), anyList());
        verify(sandbox, never()).browse(any(), anyString());
    }
}
