package com.code.pal.agent.stack;

import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.workspace.LocalProjectEnvironment;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DependencyServiceTest {
    private static final String DEPS = "{\"dependencies\":{\"react\":\"19.0.0\"}}";

    private final LocalProject project = LocalProject.create();
    private final LocalProjectEnvironment sandbox = spy(project.sandbox);
    private final List<String> installs = new ArrayList<>();
    private final List<List<String>> installCommands = new ArrayList<>();
    private final List<String> links = new ArrayList<>();
    private boolean installSucceeds = true;
    private final DependencyService service = new DependencyService(new StackPacks(new ObjectMapper()), project.files,
            sandbox, project.properties);

    DependencyServiceTest() {
        // The install step stands in for npm: it records where it ran and fills node_modules there.
        doAnswer(call -> {
            String root = call.getArgument(1);
            installs.add(root);
            installCommands.add(call.getArgument(2));
            if (!installSucceeds) return new SandboxPort.CommandResult(false, 1, "npm ERR! 404 Not Found - nope@9", "");
            project.write(root + "/node_modules/react/index.js", "module.exports = {};");
            return new SandboxPort.CommandResult(true, 0, "", "");
        }).when(sandbox).install(any(), anyString(), any());
        // Windows test hosts may not allow symlinks; record the link instead.
        doAnswer(call -> links.add(call.getArgument(1) + " -> " + call.getArgument(2))).when(sandbox).link(any(), anyString(), anyString());
    }

    @Test
    void aSuccessfulInstallerCannotCacheOrLinkAnUnusableNativeToolchain() {
        project.write("package.json", "{\"devDependencies\":{\"vite\":\"8.3.4\"}}");
        doAnswer(call -> new SandboxPort.CommandResult(false, 1, "Cannot find native binding", ""))
                .when(sandbox).exec(any(), anyString(), eq(List.of("node", "node_modules/vite/bin/vite.js", "--version")));

        var result = service.prepare(project.projectId, ".");

        assertTrue(result.failed());
        assertTrue(result.error().contains("Dependency runtime check failed"));
        assertEquals(2, installs.size(), "A native dependency repair has a bounded install budget");
        assertTrue(installCommands.getLast().contains("--include=optional"));
        assertFalse(installCommands.getLast().contains("--prefer-offline"));
        assertFalse(project.exists(installs.getLast() + "/.complete"));
        assertTrue(links.isEmpty());
    }

    @Test
    void stoppingTheLoaderCannotTriggerAnotherInstallOrPublishTheCache() {
        project.write("package.json", "{\"devDependencies\":{\"vite\":\"8.3.4\"}}");
        doAnswer(call -> new SandboxPort.CommandResult(false, -1, "", SandboxPort.CommandResult.CANCELLED))
                .when(sandbox).exec(any(), anyString(), eq(List.of("node", "node_modules/vite/bin/vite.js", "--version")));
        assertThrows(LlmCancelledException.class, () -> service.prepare(project.projectId, "."));
        assertEquals(1, installs.size());
        assertFalse(project.exists(installs.getFirst() + "/.complete"));
        assertTrue(links.isEmpty());
    }

    @Test
    void aRepairedNativeToolchainIsCachedOnlyAfterItsLoaderPasses() {
        project.write("package.json", "{\"devDependencies\":{\"vite\":\"8.3.4\"}}");
        var probes = new AtomicInteger();
        doAnswer(call -> probes.incrementAndGet() == 1
                ? new SandboxPort.CommandResult(false, 1, "Cannot find native binding", "")
                : new SandboxPort.CommandResult(true, 0, "vite/8.3.4", ""))
                .when(sandbox).exec(any(), anyString(), eq(List.of("node", "node_modules/vite/bin/vite.js", "--version")));

        assertTrue(service.prepare(project.projectId, ".").installed());
        assertFalse(service.prepare(project.projectId, ".").installed());
        assertEquals(2, installs.size());
        assertEquals(2, probes.get());
        assertTrue(project.exists(installs.getLast() + "/.complete"));
        assertEquals(2, links.size());
    }

    @Test
    void aProjectWithoutDependenciesInstallsNothing() {
        project.write("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");

        DependencyService.Preparation result = service.prepare(project.projectId, ".");

        assertFalse(result.needed());
        assertTrue(installs.isEmpty() && links.isEmpty());
    }

    @Test
    void aMernProjectInstallsAndLinksTheClientAndTheServerSeparately() {
        project.write("client/package.json", "{\"dependencies\":{\"react\":\"19.0.0\"}}")
                .write("server/package.json", "{\"dependencies\":{\"express\":\"5.1.0\"}}");

        DependencyService.Preparation result = service.prepare(project.projectId, ".");

        assertTrue(result.installed());
        assertEquals(2, installs.size(), "one set per package");
        assertEquals(List.of("client/node_modules", "server/node_modules"),
                links.stream().map(link -> link.substring(0, link.indexOf(" -> "))).toList());
        installSucceeds = false;
        project.write("server/package.json", "{\"dependencies\":{\"nope\":\"9\"}}");
        DependencyService.Preparation broken = service.prepare(project.projectId, ".");
        assertTrue(broken.failed() && broken.error().startsWith("server/"), "the failing package is named: " + broken.error());
    }

    @Test
    void installsOncePerManifestSetAndLinksEveryRootToIt() {
        project.write("package.json", DEPS).write("stage/package.json", DEPS);

        DependencyService.Preparation first = service.prepare(project.projectId, ".");
        DependencyService.Preparation second = service.prepare(project.projectId, "stage");

        assertTrue(first.installed());
        assertFalse(second.installed(), "the same manifests reuse the installed set");
        assertEquals(1, installs.size());
        String set = installs.getFirst();
        assertTrue(set.startsWith(".codepal/deps/"), set);
        assertTrue(project.exists(set + "/package.json"), "the install runs on a copy of the manifests");
        assertEquals(List.of("node_modules -> " + set + "/node_modules", "stage/node_modules -> " + set + "/node_modules"), links);
    }

    @Test
    void changedManifestsGetTheirOwnSet() {
        project.write("package.json", DEPS);
        service.prepare(project.projectId, ".");
        project.write("package.json", "{\"dependencies\":{\"react\":\"19.0.0\",\"zustand\":\"5.0.0\"}}");

        service.prepare(project.projectId, ".");

        assertEquals(2, installs.size());
        assertNotEquals(installs.get(0), installs.get(1));
    }

    @Test
    void scriptOrFormattingChangesReuseTheInstalledSet() {
        project.write("package.json", "{\"scripts\":{\"build\":\"vite build\"},\"dependencies\":{\"react\":\"19.0.0\",\"a\":\"1\"}}");
        service.prepare(project.projectId, ".");
        // What validation's trusted-scripts rewrite (or an agent's reformat) produces: same dependencies.
        project.write("package.json", "{\n  \"dependencies\": {\"a\": \"1\", \"react\": \"19.0.0\"},\n  \"scripts\": {\"build\": \"tsc -b\"}\n}");

        service.prepare(project.projectId, ".");

        assertEquals(1, installs.size(), "no reinstall when only scripts or formatting changed");
    }

    @Test
    void aFailedInstallIsReportedAndLeavesNothingBehind() {
        project.write("package.json", "{\"dependencies\":{\"nope\":\"9\"}}");
        installSucceeds = false;

        DependencyService.Preparation result = service.prepare(project.projectId, ".");

        assertTrue(result.failed());
        assertTrue(result.error().contains("nope@9"), result.error());
        assertFalse(project.exists(installs.getFirst()), "a half-installed set is removed");
        assertTrue(links.isEmpty());
        verify(sandbox, never()).link(any(), anyString(), anyString());
    }

    @Test
    void onlyTheMostRecentlyUsedSetsAreKept() {
        for (int i = 0; i < 8; i++) {
            project.write("package.json", "{\"dependencies\":{\"pkg" + i + "\":\"1.0.0\"}}");
            service.prepare(project.projectId, ".");
        }

        // Five: a MERN project's live and candidate client and server sets fit at once.
        List<String> remaining = project.files.list(project.projectId, ".codepal/deps", 1).stream()
                .filter(entry -> entry.directory()).map(entry -> entry.path()).toList();
        assertEquals(5, remaining.size(), remaining.toString());
        assertTrue(remaining.contains(installs.getLast().substring(".codepal/deps/".length())), "the set in use is kept");
        verify(sandbox, times(8)).install(any(), anyString(), any());
    }

    @Test
    void linksAreRelativeSoTheyResolveInsideAndOutsideTheSandbox() {
        assertEquals(".codepal/deps/x/node_modules", ProjectPaths.relativeTarget("node_modules", ".codepal/deps/x/node_modules"));
        assertEquals("../../../deps/x/node_modules",
                ProjectPaths.relativeTarget(".codepal/changesets/run/candidate-build/node_modules", ".codepal/deps/x/node_modules"));
    }

    @Test void lockRefreshResolvesOutsideSourceAndReusesTheCompletedSet() {
        project.write("package.json", DEPS).write("package-lock.json", "{\"packages\":{\"\":{}}}");
        doAnswer(call -> {
            String root = call.getArgument(1);
            var command = (List<?>) call.getArgument(2);
            assertEquals("install", command.get(1));
            assertTrue(command.contains("--ignore-scripts"));
            project.write(root + "/package-lock.json", "{\"lockfileVersion\":3,\"packages\":{\"\":" + DEPS + "}}");
            return new SandboxPort.CommandResult(true, 0, "", "");
        }).when(sandbox).install(any(), anyString(), any());
        var resolved = service.refreshNpmLocks(project.projectId, ".");
        assertTrue(resolved.get("package-lock.json").contains("19.0.0"));
        assertEquals("{\"packages\":{\"\":{}}}", project.read("package-lock.json"));
        assertEquals(resolved, service.refreshNpmLocks(project.projectId, "."));
        verify(sandbox).install(any(), anyString(), any());
    }
    @Test void anotherRuntimeImageCannotReuseTheSameDependencySet() {
        project.write("package.json", DEPS);
        service.prepare(project.projectId, ".");
        String original = installs.getLast();
        project.properties.getSandbox().setImage("node@sha256:" + "a".repeat(64));
        service.prepare(project.projectId, ".");
        assertNotEquals(original, installs.getLast());
        assertEquals(2, installs.size());
    }
}
