package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackPacksTest {
    private final LocalProject project = LocalProject.create();
    private final StackPacks stacks = new StackPacks(new ObjectMapper());

    @Test
    void picksTheStackFromTheProjectFiles() {
        assertEquals("static-site", detect().id(), "an empty project falls back to a static site");
        project.write("index.html", "<h1>hi</h1>");
        assertEquals("static-site", detect().id());
        project.write("package.json", "{}");
        assertEquals("node", detect().id(), "a manifest outranks a bare index.html");
    }

    @Test
    void nodeValidationRunsTheProjectsOwnScripts() {
        project.write("package.json", "{\"scripts\":{\"typecheck\":\"tsc\",\"build\":\"vite build\"}}");

        CommandPlan build = detect().resolve(project.files, project.projectId, ".", ValidationOperation.BUILD, null);
        CommandPlan tests = detect().resolve(project.files, project.projectId, ".", ValidationOperation.TESTS, null);

        assertEquals(List.of("npm", "run", "build"), build.command());
        assertFalse(tests.available());
        assertEquals(List.of("runTypeCheck", "runBuild"), detect().inspect(project.files, project.projectId, ".").availableOperations());
    }

    @Test
    void dependenciesInstallWithoutLifecycleScripts() {
        project.write("package.json", "{\"scripts\":{}}");
        assertEquals(List.of(), install(), "nothing to install without declared dependencies");

        project.write("package.json", "{\"dependencies\":{\"react\":\"19.0.0\"}}");
        assertEquals(List.of("npm", "install", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error"), install());

        project.write("package-lock.json", "{\"lockfileVersion\":3}");
        assertEquals("ci", install().get(1), "a lockfile pins exact versions");
        assertTrue(install().contains("--ignore-scripts"));
    }

    @Test
    void stagedScriptChangesAreDetectedAndNeverRun() {
        project.write("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        project.write("stage/package.json", "{\"scripts\":{\"build\":\"exit 0\"},\"dependencies\":{\"a\":\"1\"}}");
        StackPack node = detect();

        assertTrue(node.trustedCommandsChanged(project.files, project.projectId, ".", "stage"));
        node.preserveTrustedCommands(project.files, project.projectId, ".", "stage");

        String candidate = project.read("stage/package.json");
        assertTrue(candidate.contains("vite build") && !candidate.contains("exit 0"), candidate);
        assertTrue(candidate.contains("\"a\""), "the staged dependencies are kept");
    }

    private StackPack detect() {
        return stacks.detect(project.files, project.projectId, ".");
    }

    private List<String> install() {
        return detect().installCommand(project.files, project.projectId, ".");
    }
}
