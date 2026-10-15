package com.code.pal.agent.runtime;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ReleaseSourceTest {
    @Test void newFrontendBlocksOldBackendAndArtifactsDoNotAffectSourceIdentity() {
        var p = LocalProject.create(); p.write("client/src/a.ts", "A");
        String a = ReleaseSource.capture(p.files, p.projectId, ".");
        String site = UUID.randomUUID().toString(), server = ".codepal/server/" + UUID.randomUUID();
        p.write(".codepal/site/CURRENT", site);
        p.write(".codepal/site/" + site + "/SOURCE_REVISION", a); p.write(server + "/SOURCE_REVISION", a);
        assertEquals(a, ReleaseSource.capture(p.files, p.projectId, "."));
        assertTrue(ReleaseSource.paired(p.files, p.projectId, server));
        p.write("client/src/a.ts", "B");
        p.write(".codepal/site/" + site + "/SOURCE_REVISION", ReleaseSource.capture(p.files, p.projectId, "."));
        assertFalse(ReleaseSource.paired(p.files, p.projectId, server));
        p.write(server + "/SOURCE_REVISION", "malformed"); assertFalse(ReleaseSource.paired(p.files, p.projectId, server));
        assertNotEquals(AppServerService.serverName(server), AppServerService.serverName(".codepal/server/"+UUID.randomUUID()));
    }
}
