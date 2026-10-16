package com.code.pal.agent.preview;

import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedReleaseTest {
    @Test void staticSnapshotSurvivesEditsAndFailedPublish() {
        var project = LocalProject.create();
        var sites = mock(SiteBuildService.class);
        when(sites.build(project.projectId)).thenReturn(SiteBuildService.Result.notNeeded());
        var service = new PublishedSiteService(sites, project.files, project.sandbox);
        service.useStacks(new StackPacks(new ObjectMapper()));
        project.write("index.html", "A");
        service.publish(project.projectId);
        String release = service.publishedSite(project.projectId);
        assertNotNull(release);
        project.write("index.html", "B");
        assertEquals("A", project.files.readText(project.projectId, release, "index.html"));
        when(sites.build(project.projectId)).thenReturn(SiteBuildService.Result.failed("fixture failure"));
        assertThrows(IllegalStateException.class, () -> service.publish(project.projectId));
        assertEquals(release, service.publishedSite(project.projectId));
    }

    @Test void apiPublishAndLegacyUnboundSnapshotAreGated() {
        var project = LocalProject.create();
        var sites = mock(SiteBuildService.class);
        var service = new PublishedSiteService(sites, project.files, project.sandbox);
        service.useStacks(new StackPacks(new ObjectMapper()));
        project.write("server/package.json", "{}");
        assertFalse(service.capability(project.projectId).available());
        assertThrows(IllegalStateException.class, () -> service.publish(project.projectId));
        verifyNoInteractions(sites);
        String id = java.util.UUID.randomUUID().toString();
        project.write(".codepal/published/LIVE", id);
        project.write(".codepal/published/" + id + "/index.html", "legacy");
        assertNull(service.publishedSite(project.projectId));
    }

    @Test void anAppOnTheDisabledSharedSupabaseCannotPublishButOwnProjectsCan() {
        var project = LocalProject.create();
        var sites = mock(SiteBuildService.class);
        when(sites.build(project.projectId)).thenReturn(SiteBuildService.Result.notNeeded());
        var service = new PublishedSiteService(sites, project.files, project.sandbox);
        service.useStacks(new StackPacks(new ObjectMapper()));
        project.write("index.html", "A");
        var connection = mock(com.code.pal.project.supabase.SupabaseConnectionRepository.Capability.class);
        var connections = mock(com.code.pal.project.supabase.SupabaseConnectionRepository.class);
        when(connections.capability(project.projectId)).thenReturn(java.util.Optional.of(connection));
        when(connection.getMode()).thenReturn(com.code.pal.project.supabase.SupabaseConnection.Mode.SELF_HOSTED);
        service.useSupabase(connections, new com.code.pal.project.supabase.SelfHostedSupabase("", "", "postgres", ""));

        assertEquals(com.code.pal.project.supabase.SupabaseAdmission.SELF_HOSTED_BLOCKED, service.capability(project.projectId).reason());
        assertThrows(IllegalStateException.class, () -> service.publish(project.projectId));
        verifyNoInteractions(sites);
        assertNull(service.publishedSite(project.projectId));

        when(connection.getMode()).thenReturn(com.code.pal.project.supabase.SupabaseConnection.Mode.CLOUD);
        service.publish(project.projectId);
        assertNotNull(service.publishedSite(project.projectId));
    }
}
