package com.code.pal.agent.workspace;

import com.code.pal.agent.lease.ProjectLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FencedSupabaseBrowserTest {
    @Test
    @SuppressWarnings("unchecked")
    void scratchBrowserRetainsLeaseChecksBeforeAndAfterTheAdapterCall() {
        ObjectProvider<DockerProjectEnvironment> docker = mock(ObjectProvider.class);
        ObjectProvider<LocalProjectEnvironment> local = mock(ObjectProvider.class);
        var adapter = mock(DockerProjectEnvironment.class);
        var leases = mock(ProjectLeaseService.class);
        when(docker.getIfAvailable()).thenReturn(adapter);
        var boundary = new FencedSandboxPort(docker, local, leases);
        var project = UUID.randomUUID();
        var endpoint = new SandboxPort.ServerEndpoint("http://127.0.0.1:40000", Map.of());
        var result = new SandboxPort.CommandResult(true, 0, "{}", "");
        when(adapter.browseWithSupabase(project, "candidate/dist", "{}", endpoint)).thenReturn(result);
        assertSame(result, boundary.browseWithSupabase(project, "candidate/dist", "{}", endpoint));
        var order = inOrder(leases, adapter);
        order.verify(leases).checkCurrent(project);
        order.verify(adapter).browseWithSupabase(project, "candidate/dist", "{}", endpoint);
        order.verify(leases).checkCurrent(project);
        verifyNoInteractions(local);
    }
}
