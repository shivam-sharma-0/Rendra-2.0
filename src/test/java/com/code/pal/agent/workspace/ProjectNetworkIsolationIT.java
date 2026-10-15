package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.command.it",matches="true")
class ProjectNetworkIsolationIT {
    private String docker(String... args)throws Exception {
        var command=new ArrayList<>(List.of("wsl.exe","-e","docker"));command.addAll(List.of(args));var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output=process.getInputStream().readAllBytes();assertTrue(process.waitFor(60,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(output));return new String(output).trim();
    }
    private int port()throws Exception {try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    private HttpResponse<String> call(SandboxPort.ServerEndpoint endpoint, boolean signed)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(endpoint.baseUrl()+"/api/health")).timeout(Duration.ofSeconds(3));if(signed)endpoint.headers().forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void eachProcessIsPrivateAndOnlySignedRelayRequestsReachIt()throws Exception {
        String fixture=System.getProperty("codex.command.project");assertTrue(fixture.matches("codepal-codex-command-it-[a-f0-9]{12}"));
        Path base=Path.of(System.getProperty("codex.command.base")).toAbsolutePath().normalize();assertEquals(Path.of("target/codex-command-it",fixture,"workspaces").toAbsolutePath().normalize(),base);
        var props=new AgentProperties();props.setWorkspaceBasePath(base.toString());var s=props.getSandbox();s.setHostWorkspacePath(System.getProperty("codex.command.host-base"));s.setImage(System.getProperty("codex.command.image"));s.setContainerPrefix(fixture+"-");s.setMemoryMb(256);s.setServerMemoryMb(128);s.setAppsNetwork(fixture+"-apps");s.setAppsDatabaseContainer(fixture+"-mongo");s.setAppsDatabaseVolume(fixture+"-data");s.setGatewayContainer(fixture+"-gateway");s.setAppsDatabasePassword("disposable-test-only");s.setGatewayPort(port());s.setAppsDatabasePort(port());
        s.setDockerCommand("wsl.exe");s.setDockerCommandArgs("-e docker");
        var sandbox=new DockerProjectEnvironment(props);UUID first=UUID.randomUUID(),second=UUID.randomUUID();Files.createDirectories(base);Files.writeString(base.getParent().resolve("projects.txt"),first+"\n"+second+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        try {
            for(UUID project:List.of(first,second)){sandbox.ensure(project);Path release=base.resolve(project.toString()).resolve("release");Files.createDirectories(release);Files.writeString(release.resolve("api.cjs"),"require('node:http').createServer((req,res)=>{res.setHeader('content-type','application/json');res.end(JSON.stringify({ok:true,leaked:!!req.headers['x-codepal-signature']}))}).listen(process.env.PORT)");}
            var saved=sandbox.startServer(first,"saved","release",List.of("node","api.cjs"),Map.of(),3011);
            var candidate=sandbox.startServer(first,"candidate","release",List.of("node","api.cjs"),Map.of(),3012);
            var foreign=sandbox.startServer(second,"saved","release",List.of("node","api.cjs"),Map.of(),3013);
            for(var endpoint:List.of(saved,candidate,foreign)){HttpResponse<String> response=null;for(int i=0;i<20;i++){response=call(endpoint,true);if(response.statusCode()==200)break;Thread.sleep(100);}assertEquals(200,response.statusCode());assertEquals("{\"ok\":true,\"leaked\":false}",response.body());assertEquals(403,call(endpoint,false).statusCode());}
            String container=docker("ps","-q","--filter","label=com.codepal.project="+first,"--filter","label=com.codepal.port=3012");assertFalse(container.isBlank());
            assertEquals("{}",docker("inspect","-f","{{json .HostConfig.PortBindings}}",container));
            var mapper=new tools.jackson.databind.ObjectMapper();var networks=mapper.readTree(docker("inspect","-f","{{json .NetworkSettings.Networks}}",container));assertEquals(1,networks.size());
            String network=networks.propertyNames().iterator().next();assertEquals("true",docker("network","inspect","-f","{{.Internal}}",network));
            for(String upstream:List.of(saved.headers().get("x-codepal-upstream"),foreign.headers().get("x-codepal-upstream"),"1.1.1.1:443")) {
                String[] pair=upstream.split(":");String probe="const s=require('node:net').connect({host:process.argv[1],port:Number(process.argv[2])});s.setTimeout(1000);s.on('connect',()=>process.exit(7));s.on('error',()=>process.exit(0));s.on('timeout',()=>process.exit(0));";
                assertEquals("",docker("exec",container,"node","-e",probe,pair[0],pair[1]),"Candidate cannot reach another process or the internet");
            }
            sandbox.stopServer(first,"candidate");assertNull(sandbox.server(first,"candidate"));assertEquals(200,call(sandbox.server(first,"saved"),true).statusCode());
            assertFalse(new DockerProjectEnvironment(props).databaseAdminUri().isBlank(),"Restart can select Mongo's trusted network even after private networks were added");
        } finally {
            try{sandbox.destroy(first);}finally{sandbox.destroy(second);}
            for(String helper:List.of(fixture+"-gateway",fixture+"-mongo")){String found=docker("ps","-aq","--filter","name=^/"+helper+"$");if(!found.isBlank()){assertEquals("/"+helper,docker("inspect","-f","{{.Name}}",found));docker("rm","-f",found);}}
            if(!docker("volume","ls","-q","--filter","name=^"+fixture+"-data$").isBlank())docker("volume","rm",fixture+"-data");
            if(!docker("network","ls","-q","--filter","name=^"+fixture+"-apps$").isBlank())docker("network","rm",fixture+"-apps");
        }
    }
}
