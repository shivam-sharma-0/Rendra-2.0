package com.code.pal.agent.preview;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.LocalProjectEnvironment;
import com.code.pal.agent.workspace.SandboxPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Drafts follow the agent's staged changes, never touch the served site, and go away with the run. */
class DraftPreviewServiceTest {
    private static final String VITE = "{\"devDependencies\":{\"vite\":\"7.0.0\"},\"scripts\":{\"build\":\"vite build\"}}";
    private static final String STAGED = ".codepal/changesets/run/workspace";

    private final LocalProject project = LocalProject.create(properties());
    private final LocalProjectEnvironment sandbox = spy(project.sandbox);
    private final DependencyService dependencies = mock(DependencyService.class);
    private final AtomicInteger builds = new AtomicInteger();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final SiteBuildService sites = new SiteBuildService(new StackPacks(new ObjectMapper()), project.files, sandbox, dependencies);
    private final PreviewService preview = new PreviewService(project.files, sites, new PublishedSiteService(sites, project.files, sandbox));
    private final DraftPreviewService drafts = new DraftPreviewService(sites, project.files, sandbox, project.properties,
            new DraftPreviewService.DraftListener() {
                @Override public void drafted(UUID projectId, UUID runId, String version) { events.add("drafted " + version); }
                @Override public void ended(UUID projectId, UUID runId) { events.add("ended"); }
            });
    private final UUID runId = UUID.randomUUID();

    DraftPreviewServiceTest() {
        when(dependencies.prepare(any(), anyString())).thenReturn(new DependencyService.Preparation(true, false, null));
        // Stands in for `vite build`: compiles the copy's src/App.tsx into dist/, or fails on BROKEN.
        doAnswer(call -> {
            String root = call.getArgument(1);
            builds.incrementAndGet();
            String app = project.read(root + "/src/App.tsx");
            if (app.contains("BROKEN")) return new SandboxPort.CommandResult(false, 1, "error TS1005", "");
            project.write(root + "/dist/index.html", "<h1>" + app.strip() + "</h1>");
            return new SandboxPort.CommandResult(true, 0, "", "");
        }).when(sandbox).exec(any(), anyString(), any());
    }

    @AfterEach
    void stop() {
        drafts.stop();
    }

    @Test
    void theStagedChangesAreBuiltIntoADraftWhileTheServedSiteStaysAsItIs() throws Exception {
        project.write("package.json", VITE).write("src/App.tsx", "live");
        sites.build(project.projectId);
        project.write(STAGED + "/package.json", VITE).write(STAGED + "/src/App.tsx", "draft one");

        drafts.changed(project.projectId, runId, STAGED);
        String site = awaitDraft();

        assertEquals("<h1>draft one</h1>", text(preview.serveSite(project.projectId, site, "index.html")));
        assertEquals("<h1>live</h1>", text(preview.serve(project.projectId, "index.html")), "the committed site is untouched");
        assertEquals(new DraftPreviewService.ActiveDraft(runId, site.substring(site.lastIndexOf('/') + 1)),
                drafts.activeDraft(project.projectId));
        assertNull(drafts.draftSite(UUID.randomUUID(), runId), "a draft is only this project's");
    }

    @Test
    void writesInQuickSuccessionBuildOnceAndABrokenDraftKeepsThePreviousOne() throws Exception {
        project.write(STAGED + "/package.json", VITE).write(STAGED + "/src/App.tsx", "first");
        drafts.changed(project.projectId, runId, STAGED);
        String first = awaitDraft();
        builds.set(0);

        project.write(STAGED + "/src/App.tsx", "BROKEN half-written");
        for (int i = 0; i < 5; i++) drafts.changed(project.projectId, runId, STAGED);
        awaitBuilds(1);
        Thread.sleep(300);

        assertEquals(1, builds.get(), "debounced: one build for the burst");
        assertEquals(first, drafts.draftSite(project.projectId, runId), "the last good draft stays");
        assertEquals(1, events.stream().filter(event -> event.startsWith("drafted")).count());
    }

    @Test
    void aStaticSiteDraftIsItsOwnFilesFromTheEntryFolder() throws Exception {
        project.write(STAGED + "/client/index.html", "<h1>static draft</h1>").write(STAGED + "/client/app.js", "go()");

        drafts.changed(project.projectId, runId, STAGED);
        String site = awaitDraft();

        assertEquals("<h1>static draft</h1>", text(preview.serveSite(project.projectId, site, "")));
        assertEquals("go()", text(preview.serveSite(project.projectId, site, "app.js")));
        assertEquals(0, builds.get(), "nothing to compile");
    }

    @Test
    void finishingTheRunRemovesTheDraftAndTellsTheViewers() throws Exception {
        project.write(STAGED + "/index.html", "<h1>draft</h1>");
        drafts.changed(project.projectId, runId, STAGED);
        awaitDraft();

        drafts.finish(runId);

        assertNull(drafts.draftSite(project.projectId, runId));
        assertNull(drafts.activeDraft(project.projectId));
        assertFalse(project.exists(DraftPreviewService.DRAFTS + "/" + runId));
        assertEquals("ended", events.getLast());
        drafts.changed(project.projectId, runId, STAGED);   // a late write after the run is not built
    }

    private String awaitDraft() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String site = drafts.draftSite(project.projectId, runId);
            if (site != null) return site;
            Thread.sleep(50);
        }
        fail("no draft was built");
        return null;
    }

    private void awaitBuilds(int count) throws InterruptedException {
        for (int i = 0; i < 100 && builds.get() < count; i++) Thread.sleep(50);
    }

    private static AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setDraftPreviewQuietMs(100);
        return properties;
    }

    private static String text(PreviewService.PreviewFile file) {
        return new String(file.content(), StandardCharsets.UTF_8);
    }
}
