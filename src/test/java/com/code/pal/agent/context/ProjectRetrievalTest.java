package com.code.pal.agent.context;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.impl.FindProjectFilesTool;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProjectRetrievalTest {
    private DeterministicProjectRetrieval service(AgentProperties props){return new DeterministicProjectRetrieval(props,new ProjectContextService(props,new ObjectMapper()));}
    @Test void locatesSymbolsAndForwardReverseImportsWithoutBodies() throws Exception {
        var p=LocalProject.create();Files.createDirectories(p.dir().resolve("src"));
        Files.writeString(p.dir().resolve("src/Login.tsx"),"import { resetPassword } from './auth'; export function Login() { /* PRIVATE_BODY */ }");
        Files.writeString(p.dir().resolve("src/auth.ts"),"export function resetPassword() { /* SECRET_BODY */ }");
        var result=service(new AgentProperties()).search(p.files,p.projectId,".","resetPassword",10);
        assertEquals("src/auth.ts",result.matches().getFirst().path());
        assertEquals(java.util.List.of("src/Login.tsx"),result.matches().getFirst().importedBy());
        assertTrue(result.matches().stream().anyMatch(m->m.path().equals("src/Login.tsx")));
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("SECRET_BODY"));
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("PRIVATE_BODY"));
    }
    @Test void stagedAndAcceptedSnapshotsAreSeparateAndEditsInvalidateVersions() throws Exception {
        var p=LocalProject.create();Files.writeString(p.dir().resolve("App.tsx"),"export const Before=1;");
        Files.createDirectories(p.dir().resolve(".codepal/stage"));Files.writeString(p.dir().resolve(".codepal/stage/App.tsx"),"export const After=2;");
        var s=service(new AgentProperties());var accepted=s.search(p.files,p.projectId,".","Before",10);
        var staged=s.search(p.files,p.projectId,".codepal/stage","After",10);
        assertNotEquals(accepted.snapshotDigest(),staged.snapshotDigest());
        assertNotEquals(accepted.matches().getFirst().version(),staged.matches().getFirst().version());
        Files.writeString(p.dir().resolve("App.tsx"),"export const Before=3;");
        assertNotEquals(accepted.snapshotDigest(),s.search(p.files,p.projectId,".","Before",10).snapshotDigest());
    }
    @Test void assetsKeepOriginalPathAndSecretsAndInternalTreesAreExcluded() throws Exception {
        var p=LocalProject.create();Files.createDirectories(p.dir().resolve("assets"));
        Files.write(p.dir().resolve("assets/logo.png"),new byte[]{0,1,2});
        Files.writeString(p.dir().resolve("credentials"),"export const logoSecret=1;");
        Files.writeString(p.dir().resolve(".env"),"logoSecret=value");
        Files.writeString(p.dir().resolve("RENDRA.md"),"logoSecret");
        var r=service(new AgentProperties()).search(p.files,p.projectId,".","logo",10);
        assertEquals(1,r.matches().size());assertEquals("assets/logo.png",r.matches().getFirst().path());assertTrue(r.matches().getFirst().asset());
        assertTrue(r.matches().getFirst().version().startsWith("sha256:"));
    }
    @Test void boundedScanningAndPartialSummariesAreVisibleAndToolIsReadOnly() throws Exception {
        var p=LocalProject.create();Files.writeString(p.dir().resolve("A.ts"),"export const Feature=1;\n"+"// big\n".repeat(4000));Files.writeString(p.dir().resolve("B.ts"),"export const Feature=2;");
        var props=new AgentProperties();props.setProjectScanMaxFiles(1);var tool=new FindProjectFilesTool(service(props));
        var r=(ProjectRetrieval.Result)tool.execute(Map.of("query","Feature","limit",1),new AgentContext(p.projectId,1L,ProjectType.CHAT,".",p.files));
        assertTrue(r.partial());assertTrue(r.matches().getFirst().summaryPartial());assertEquals(1,r.scannedFiles());assertFalse(tool.mutating());
        assertThrows(IllegalArgumentException.class,()->tool.execute(Map.of("query","Feature","limit",1.5),new AgentContext(p.projectId,1L,ProjectType.CHAT,".",p.files)));
    }
}
