package com.code.pal.agent.contract;

import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaticAssetsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void staticBuildUsesPinnedPackagesAndOutranksNodeWithoutChangingStackIdentity() {
        var project = LocalProject.create();
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        var output = new ContractScaffold(mapper).render(contract, "Bakery", path -> null);
        output.forEach(project::write);
        project.write("index.html", "<!doctype html><h1 class='text-red-500'>Bakery</h1>");
        var manifest = mapper.readTree(output.get("package.json"));
        assertEquals("4.3.3", manifest.path("devDependencies").path("tailwindcss").asString());
        assertEquals("4.3.3", manifest.path("devDependencies").path("@tailwindcss/node").asString());
        assertTrue(manifest.path("scripts").isMissingNode());
        assertFalse(output.values().stream().anyMatch(text -> text.contains("cdn.jsdelivr.net")));
        var pack = new StackPacks(mapper).detect(project.files, project.projectId, ".");
        assertEquals("static-site", pack.id());
        assertTrue(pack.resolveFixed(project.files, project.projectId, ".", ValidationOperation.BUILD).available());
        assertEquals(List.of("dist"), pack.previewBuild(project.files, project.projectId, ".").outputDirectories());
        assertTrue(pack.installCommand(project.files, project.projectId, ".").contains("--ignore-scripts"));
    }

    @Test
    void tamperedStaticCompilerManifestFailsClosedInsteadOfFallingBackToNode() {
        var project = LocalProject.create();
        StaticAssets.files(ContractFixtures.ID).forEach(project::write);
        project.write("index.html", "<h1>App</h1>");
        project.write("package.json", "{\"scripts\":{\"build\":\"echo unsafe\"}}");
        var pack = new StackPacks(mapper).detect(project.files, project.projectId, ".");
        assertEquals("static-site", pack.id());
        assertFalse(pack.resolveFixed(project.files, project.projectId, ".", ValidationOperation.BUILD).available());
        assertThrows(IllegalArgumentException.class, () -> pack.previewBuild(project.files, project.projectId, "."));
        assertThrows(IllegalArgumentException.class, () -> pack.installCommand(project.files, project.projectId, "."));
    }

    @Test
    void plainLegacyStaticSiteStillNeedsNoCompilerOrPackages() {
        var project = LocalProject.create().write("index.html", "<h1>Legacy</h1>");
        var pack = new StackPacks(mapper).detect(project.files, project.projectId, ".");
        assertEquals("static-site", pack.id());
        assertNull(pack.previewBuild(project.files, project.projectId, "."));
        assertTrue(pack.installCommand(project.files, project.projectId, ".").isEmpty());
    }

    @Test
    void finalGateRejectsTrustedPendingStubsButAllowsRealComponents() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        assertThrows(IllegalArgumentException.class, () -> ContractCompletion.require(contract,
                path -> "export default function PendingApp() { return null }\n"));
        assertDoesNotThrow(() -> ContractCompletion.require(contract,
                path -> "export default function App() { return <h1>Tasks</h1> }\n"));
    }
}
