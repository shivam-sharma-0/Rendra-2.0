package com.code.pal.agent.contract;

import com.code.pal.agent.runtime.ReleaseSource;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.starter.Starter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit package gate: real pinned compiler and existing isolated install/build machinery, no model or DB. */
@EnabledIfSystemProperty(named = "codepal.static.it", matches = "true")
class StaticAssetBuildIT {
    @Test
    void exportsProductionInputsForTheLinuxCompilerGate() throws Exception {
        Path root = Path.of("target/codex-static-inputs").toAbsolutePath().normalize();
        Files.createDirectories(root);
        for (var file : Starter.STATIC_SITE.files("Appointments").entrySet()) {
            Path target = root.resolve(file.getKey()).normalize();
            assertTrue(target.startsWith(root));
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        try (var stream = getClass().getClassLoader().getResourceAsStream("starters/static-site/build.mjs")) {
            assertNotNull(stream);
            Files.write(root.resolve("compiler.mjs"), stream.readAllBytes());
        }
    }

    @Test
    void compilesRealUtilitiesIntoAnIsolatedArtifactWithoutChangingSource() {
        var project = LocalProject.create();
        StaticAssets.files(ContractFixtures.ID).forEach(project::write);
        project.write("index.html", "<!doctype html><html><head><title>Fixture</title></head><body class='text-red-500 p-4'>Fixture</body></html>");
        project.write("rendra/" + ContractFixtures.ID + "/theme.css", ":root { --rendra-accent: #123456 }\n");
        String before = ReleaseSource.capture(project.files, project.projectId, ".");
        String copy = ".codepal/static-asset-it";
        project.sandbox.copyTree(project.projectId, ".", copy);
        var stacks = new StackPacks(new ObjectMapper());
        var dependencies = new DependencyService(stacks, project.files, project.sandbox, project.properties);
        var prepared = dependencies.prepare(project.projectId, copy);
        assertFalse(prepared.failed(), prepared.error());
        var plan = stacks.detect(project.files, project.projectId, copy).previewBuild(project.files, project.projectId, copy);
        stacks.detect(project.files, project.projectId, copy).prepareBuild(project.files, project.projectId, copy);
        var result = project.sandbox.exec(project.projectId, copy, plan.command());
        assertTrue(result.success(), result.stdout() + result.error());
        String css = project.files.readText(project.projectId, copy + "/dist", "rendra.css");
        assertTrue(css.contains(".text-red-500"));
        assertTrue(css.contains(".p-4"));
        assertTrue(css.contains("--rendra-accent: #123456"));
        assertTrue(css.contains(".app-container"));
        assertTrue(css.contains("--app-accent"));
        String html = project.files.readText(project.projectId, copy + "/dist", "index.html");
        assertTrue(html.contains("href=\"rendra.css\""));
        assertFalse(html.contains("cdn."));
        assertFalse(project.exists("dist"));
        assertEquals(before, ReleaseSource.capture(project.files, project.projectId, "."));
        project.files.writeText(project.projectId, copy, "index.html",
                "<script src='https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4'></script>");
        assertFalse(project.sandbox.exec(project.projectId, copy, plan.command()).success(), "Runtime CDN must fail compilation");
    }
}
