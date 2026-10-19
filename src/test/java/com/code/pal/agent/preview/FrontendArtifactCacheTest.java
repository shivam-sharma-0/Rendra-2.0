package com.code.pal.agent.preview;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.stack.NodePack;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FrontendArtifactCacheTest {
    private static final String COMPILER = ".codepal/compiler", RUNTIME = ".codepal/runtime";
    private final AgentProperties properties = new AgentProperties();
    private final LocalProject project = LocalProject.create(properties);
    private final NodePack stack = new NodePack(new ObjectMapper());
    private final AgentContext context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, ".", project.files);
    private final FrontendArtifactCache cache = new FrontendArtifactCache(properties, project.files, project.sandbox);

    private StackPack.PreviewBuild ready() {
        properties.getBuildArtifacts().setEnabled(true);
        properties.getSandbox().setRequired(true);
        seed(COMPILER);
        seed(RUNTIME);
        project.write(COMPILER + "/dist/index.html", "<main>Verified fixture</main>");
        project.files.writeBytes(project.projectId, COMPILER + "/dist/icon.png", new byte[]{1,2,3,4});
        return stack.diagnosticBuild(project.files, project.projectId, COMPILER);
    }

    private void seed(String root) {
        Starter.VITE_REACT.files(null).forEach((path, body) -> project.write(root + "/" + path, body));
        project.write(root + "/src/App.tsx", "export default function App(){return <main>Fixture</main>}");
    }

    @Test
    void exactInputsReuseAnImmutableBinarySnapshotAndReplaceStaleOutput() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        assertNotNull(token);
        assertTrue(cache.capture(token, context, COMPILER, stack, build));
        project.write(COMPILER + "/dist/index.html", "Changed after capture");
        project.write(RUNTIME + "/dist/stale.txt", "Stale output");
        assertEquals(RUNTIME + "/dist", cache.restore(token, context, RUNTIME, stack, build));
        assertEquals("<main>Verified fixture</main>", project.read(RUNTIME + "/dist/index.html"));
        assertFalse(project.exists(RUNTIME + "/dist/stale.txt"));
        assertArrayEquals(new byte[]{1,2,3,4}, project.files.readBytes(project.projectId, RUNTIME + "/dist", "icon.png", 10));
    }

    @Test
    void aNestedSourceFolderNamedBuildIsAnInputAndCannotBeTreatedAsOutput() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        assertTrue(cache.capture(token, context, COMPILER, stack, build));
        project.write(RUNTIME + "/src/build/config.ts", "export const amount = 9");
        assertNull(cache.restore(token, context, RUNTIME, stack, build));
    }

    @Test
    void sourceEnvironmentAndCompilerArgumentsCannotReuseAnotherArtifact() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        assertTrue(cache.capture(token, context, COMPILER, stack, build));
        project.write(RUNTIME + "/src/App.tsx", "export default function App(){return <main>Changed</main>}");
        assertNull(cache.restore(token, context, RUNTIME, stack, build));
        seed(RUNTIME);
        project.write(RUNTIME + "/.env.production.local", "VITE_LABEL=Changed");
        assertNull(cache.restore(token, context, RUNTIME, stack, build));
        var command = new ArrayList<>(build.command()); command.add("--sourcemap");
        assertNull(cache.restore(token, context, COMPILER, stack, new StackPack.PreviewBuild(command, build.outputDirectories())));
    }

    @Test
    void changedBuildInputsCannotBeCapturedAndCustomConfigurationKeepsNormalCompilation() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        project.write(COMPILER + "/src/App.tsx", "Changed while compiling");
        assertFalse(cache.capture(token, context, COMPILER, stack, build));
        project.write(COMPILER + "/vite.config.ts", "export default {plugins:[]}");
        assertNull(cache.token(context, COMPILER, stack, build));
    }

    @Test
    void aDifferentRunCannotReadAnArtifactAndCleanupForgetsIt() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        assertTrue(cache.capture(token, context, COMPILER, stack, build));
        var another = new AgentContext(project.projectId, 7L, ProjectType.BUILD, ".", project.files);
        var otherToken = cache.token(another, RUNTIME, stack, build);
        assertNull(cache.restore(otherToken, another, RUNTIME, stack, build));
        seed(".");
        assertNull(cache.restore(token, context, ".", stack, build), "Cache never writes a live project output");
        cache.clear(context.runId());
        assertNull(cache.restore(token, context, RUNTIME, stack, build));
    }

    @Test
    void capacityAndRolloutLimitsFallBackWithoutClaimingAnArtifact() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        properties.getBuildArtifacts().setMaxRunBytes(1);
        assertFalse(cache.capture(token, context, COMPILER, stack, build));
        properties.getBuildArtifacts().setEnabled(false);
        assertNull(cache.token(context, COMPILER, stack, build));
        properties.getBuildArtifacts().setEnabled(true);
        properties.getSandbox().setImage("node:latest");
        assertNull(cache.token(context, COMPILER, stack, build));
    }

    @Test
    void stopBeforeRestoreCannotWriteAnyOutput() {
        var build = ready();
        var token = cache.token(context, COMPILER, stack, build);
        assertTrue(cache.capture(token, context, COMPILER, stack, build));
        var cancellation = new RunCancellation(context.runId()); cancellation.cancel();
        try (var ignored = RunCancellation.bind(cancellation)) {
            assertThrows(LlmCancelledException.class, () -> cache.restore(token, context, RUNTIME, stack, build));
        }
        assertFalse(project.exists(RUNTIME + "/dist/index.html"));
    }
}
