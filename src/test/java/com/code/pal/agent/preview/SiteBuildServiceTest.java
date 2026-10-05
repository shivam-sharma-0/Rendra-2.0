package com.code.pal.agent.preview;

import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.LocalProjectEnvironment;
import com.code.pal.agent.workspace.SandboxPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Site builds switch atomically, keep the last good site on failure, and are what the preview serves. */
class SiteBuildServiceTest {
    private static final String VITE = "{\"devDependencies\":{\"vite\":\"7.0.0\"},\"scripts\":{\"build\":\"vite build\"}}";

    private final LocalProject project = LocalProject.create();
    private final LocalProjectEnvironment sandbox = spy(project.sandbox);
    private final DependencyService dependencies = mock(DependencyService.class);
    private final List<List<String>> commands = new ArrayList<>();
    private String nextBuildOutput = "<h1>v1</h1>";
    private final SiteBuildService sites = new SiteBuildService(new StackPacks(new ObjectMapper()), project.files, sandbox, dependencies);
    private final PreviewService preview = new PreviewService(project.files, sites);

    SiteBuildServiceTest() {
        when(dependencies.prepare(any(), anyString())).thenReturn(new DependencyService.Preparation(true, false, null));
        // Stands in for `vite build`: writes dist/ in the build's working copy, or fails.
        doAnswer(call -> {
            String root = call.getArgument(1);
            commands.add(call.getArgument(2));
            if (nextBuildOutput == null) return new SandboxPort.CommandResult(false, 1, "error TS2304: Cannot find name 'x'", "");
            project.write(root + "/dist/index.html", nextBuildOutput)
                    .write(root + "/dist/assets/app.js", "console.log('app')");
            return new SandboxPort.CommandResult(true, 0, "", "");
        }).when(sandbox).exec(any(), anyString(), any());
    }

    @Test
    void aViteProjectIsBuiltWithARelativeBaseAndServed() {
        project.write("package.json", VITE).write("index.html", "<script src=/src/main.tsx></script>");

        SiteBuildService.Result result = sites.build(project.projectId);

        assertTrue(result.built(), String.valueOf(result.error()));
        assertEquals(List.of("node", "node_modules/vite/bin/vite.js", "build", "--base", "./", "--outDir", "dist", "--emptyOutDir"),
                commands.getFirst());
        assertEquals("<h1>v1</h1>", text(preview.serve(project.projectId, "index.html")));
        assertEquals("text/javascript; charset=utf-8", preview.serve(project.projectId, "assets/app.js").contentType());
        assertTrue(project.files.list(project.projectId, ".codepal/site-build", 1).isEmpty(), "the working copy is removed");
    }

    @Test
    void aFailedBuildKeepsTheLastGoodSite() {
        project.write("package.json", VITE);
        sites.build(project.projectId);
        nextBuildOutput = null;

        SiteBuildService.Result failed = sites.build(project.projectId);

        assertNotNull(failed.error());
        assertTrue(failed.error().contains("TS2304"), failed.error());
        assertEquals("<h1>v1</h1>", text(preview.serve(project.projectId, "index.html")));
    }

    @Test
    void theNewSiteReplacesTheOldOneAndOnlyThePreviousIsKept() {
        project.write("package.json", VITE);
        for (String version : List.of("v1", "v2", "v3")) {
            nextBuildOutput = "<h1>" + version + "</h1>";
            sites.build(project.projectId);
        }

        assertEquals("<h1>v3</h1>", text(preview.serve(project.projectId, "")));
        long kept = project.files.list(project.projectId, ".codepal/site", 1).stream().filter(entry -> entry.directory()).count();
        assertEquals(2, kept, "current plus the previous one");
    }

    @Test
    void aStaticProjectIsServedFromItsFilesAndRetiresAnOldBuild() {
        project.write("package.json", VITE);
        sites.build(project.projectId);
        project.files.delete(project.projectId, "package.json");
        project.write("index.html", "<h1>static</h1>");

        SiteBuildService.Result result = sites.build(project.projectId);

        assertFalse(result.built());
        assertNull(result.error());
        assertNull(sites.currentSite(project.projectId));
        assertEquals("<h1>static</h1>", text(preview.serve(project.projectId, "index.html")));
    }

    @Test
    void routesFallBackToTheAppButMissingAssetsAre404() {
        project.write("package.json", VITE);
        sites.build(project.projectId);

        assertEquals("<h1>v1</h1>", text(preview.serve(project.projectId, "settings/profile")), "client-side route");
        assertEquals("console.log('app')", text(preview.serve(project.projectId, "settings/assets/app.js")),
                "a relative asset requested from a nested route");
        assertThrows(ResponseStatusException.class, () -> preview.serve(project.projectId, "assets/missing.js"));
        assertEquals("<h1>v1</h1>", text(preview.serve(project.projectId, ".codepal/site/CURRENT")),
                "internal files are never served");
    }

    private static String text(PreviewService.PreviewFile file) {
        return new String(file.content(), StandardCharsets.UTF_8);
    }
}
