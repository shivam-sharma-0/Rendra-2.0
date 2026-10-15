package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateServerStopTest {
    @TempDir Path root;
    final UUID project=UUID.randomUUID();
    final List<List<String>> calls=new ArrayList<>();
    DockerProjectEnvironment adapter(SandboxPort.CommandResult... replies){
        var queue=new ArrayDeque<>(List.of(replies));var props=new AgentProperties();props.setWorkspaceBasePath(root.toString());
        return new DockerProjectEnvironment(props,new WorkspaceStorage(props),(args,timeout,input,limit)->{calls.add(args);return queue.removeFirst();},Clock.systemUTC());
    }
    SandboxPort.CommandResult ok(String text){return new SandboxPort.CommandResult(true,0,text,"");}
    SandboxPort.CommandResult failed(){return new SandboxPort.CommandResult(false,1,"","Docker unavailable");}
    String namespace(){var props=new AgentProperties();props.setWorkspaceBasePath(root.toString());return new DockerProjectEnvironment(props).workspaceNamespace();}
    String labels(UUID owner){return "{\"com.codepal.app\":\"true\",\"com.codepal.project\":\""+owner+"\",\"com.codepal.namespace\":\""+namespace()+"\"}";}
    @Test void absenceRequiresAWorkingDockerQueryAndIsIdempotent(){adapter(ok(""),ok("")).stopServer(project,"check-123");assertEquals(2,calls.size());assertThrows(RuntimeException.class,()->adapter(failed()).stopServer(project,"check-123"));}
    @Test void uncertainRemovalOrRemainingProcessDoesNotAllowScratchCleanup(){assertThrows(RuntimeException.class,()->adapter(ok("owned"),ok(labels(project)),failed()).stopServer(project,"check-123"));assertThrows(RuntimeException.class,()->adapter(ok("owned"),ok(labels(project)),ok("removed"),ok("still-present")).stopServer(project,"check-123"));}
    @Test void onlyVerifiedOwnedProcessesAreRemovedAndAbsenceIsConfirmed(){assertThrows(RuntimeException.class,()->adapter(ok("foreign"),ok(labels(UUID.randomUUID()))).stopServer(project,"check-123"));assertTrue(calls.stream().noneMatch(call->call.getFirst().equals("rm")));calls.clear();adapter(ok("owned"),ok(labels(project)),ok("removed"),ok(""),ok("")).stopServer(project,"check-123");assertEquals(5,calls.size());assertEquals(List.of("rm","-f","codepal-app-"+namespace()+"-"+project+"-check-123"),calls.get(2));}
}
