package com.code.pal.agent.runtime;
import com.code.pal.agent.stack.*;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.database.GeneratedDatabaseService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AppServerReleaseTest {
    @Test void correctArtifactProcessIsUsedAndMismatchedFrontendNeverReachesIt() throws Exception {
        var p=LocalProject.create();var sandbox=mock(SandboxPort.class);var mapper=new ObjectMapper();
        var service=new AppServerService(new StackPacks(mapper),p.files,sandbox,mock(DependencyService.class),mock(GeneratedDatabaseService.class),p.properties,mapper);
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        http.createContext("/api/value",exchange->{requests.incrementAndGet();byte[] body="B".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});http.start();
        try {
            String build=".codepal/server/"+UUID.randomUUID(),site=UUID.randomUUID().toString(),proof="a".repeat(64);
            p.write(".codepal/server/CURRENT",build.substring(build.lastIndexOf('/')+1));p.write(build+"/package.json","{}");
            p.write(build+"/SOURCE_REVISION",proof);p.write(".codepal/site/CURRENT",site);p.write(".codepal/site/"+site+"/SOURCE_REVISION",proof);
            when(sandbox.server(p.projectId,AppServerService.serverName(build))).thenReturn(new SandboxPort.ServerEndpoint("http://127.0.0.1:"+http.getAddress().getPort(),Map.of()));
            var response=new MockHttpServletResponse();service.proxy(p.projectId,new MockHttpServletRequest("GET","/api/value"),response);
            assertEquals(200,response.getStatus());assertEquals("B",response.getContentAsString());assertEquals(1,requests.get());
            verify(sandbox,never()).server(p.projectId,AppServerService.APP);
            p.write(".codepal/site/"+site+"/SOURCE_REVISION","b".repeat(64));
            var refused=new MockHttpServletResponse();service.proxy(p.projectId,new MockHttpServletRequest("GET","/api/value"),refused);
            assertEquals(503,refused.getStatus());assertEquals(1,requests.get());
        }finally{http.stop(0);service.stop();}
    }
    @Test void failedNewBuildDoesNotProxyAnOlderRunningEndpoint() throws Exception {
        var p=LocalProject.create();p.write("client/package.json","{}").write("server/package.json","{}");
        var sandbox=mock(SandboxPort.class);var deps=mock(DependencyService.class);var mapper=new ObjectMapper();
        when(deps.prepare(any(),anyString())).thenReturn(new DependencyService.Preparation(false,true,"fixture dependency unavailable"));
        var service=new AppServerService(new StackPacks(mapper),p.files,sandbox,deps,mock(GeneratedDatabaseService.class),p.properties,mapper);
        try {
            service.deploy(p.projectId);assertEquals(AppServerService.Status.State.FAILED,service.status(p.projectId).state());
            var response=new MockHttpServletResponse();service.proxy(p.projectId,new MockHttpServletRequest("GET","/api/value"),response);
            assertEquals(503,response.getStatus());verify(sandbox,never()).server(any(),anyString());
        }finally{service.stop();}
    }
}
