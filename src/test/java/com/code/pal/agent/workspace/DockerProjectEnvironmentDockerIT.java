package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the real Docker daemon with project folders in WSL ext4, bind-mounted into containers
 * and read by this (Windows) JVM through the WSL share. Opt-in, because it creates containers and
 * folders (removed afterwards): {@code mvnw test -Dtest=DockerProjectEnvironmentDockerIT -Dcodepal.docker.it=true}.
 * Defaults to {@code wsl.exe -e docker}; override with -Dcodepal.docker.command / -Dcodepal.docker.args.
 */
@EnabledIfSystemProperty(named = "codepal.docker.it", matches = "true")
class DockerProjectEnvironmentDockerIT {

    private static final String DISTRO = System.getProperty("codepal.wsl.distro", "Ubuntu-24.04");
    private static final String LINUX_BASE = System.getProperty("codepal.wsl.base", "/home/shivam/codepal-it/workspaces");

    private final AgentProperties properties = properties();
    private final List<String> containers = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (String container : containers) docker("rm", "-f", container);
        docker("run", "--rm", "--user", "1000:1000", "--mount", "type=bind,source=" + LINUX_BASE.replaceAll("/[^/]+$", "")
                + ",target=/it", properties.getSandbox().getImage(), "rm", "-rf", "/it/workspaces");
    }

    @Test
    void concurrentFirstUseOfAProjectDoesNotConflict() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        UUID projectId = newProject();   // no folder or container exists yet: the original race

        int callers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<SandboxPort.CommandResult>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return sandbox.exec(projectId, List.of("cat", "/etc/hostname"));
                }));
            }
            start.countDown();
            for (Future<SandboxPort.CommandResult> result : results) {
                assertEquals(0, result.get(3, TimeUnit.MINUTES).exitCode());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void containerAndBackendShareTheProjectFolderWithMatchingOwnership() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        UUID projectId = newProject();
        Path backendView = new WorkspaceStorage(properties).projectDir(projectId);
        sandbox.ensure(projectId);

        Files.writeString(backendView.resolve("from-backend.txt"), "written by the backend");
        assertEquals("written by the backend", sandbox.exec(projectId, List.of("cat", "from-backend.txt")).stdout().trim());

        assertEquals(0, sandbox.exec(projectId, List.of("sh", "-c", "mkdir -p dist && echo built > dist/index.html")).exitCode());
        assertEquals("built", Files.readString(backendView.resolve("dist/index.html")).trim());

        // Both sides can replace and delete each other's files: nothing is owned by root.
        Files.delete(backendView.resolve("dist/index.html"));
        assertEquals(0, sandbox.exec(projectId, List.of("rm", "from-backend.txt")).exitCode());
        assertEquals("1000:1000", sandbox.exec(projectId, List.of("stat", "-c", "%u:%g", ".")).stdout().trim());
    }

    @Test
    void generatedCodeHasNoNetworkAndOldContainersAreRecreatedOnTheProjectFolder() throws Exception {
        UUID projectId = newProject();
        String container = new DockerProjectEnvironment(properties).primaryContainerName(projectId);
        String volume = "codepal-project-" + projectId;
        try {
            // A container as earlier code created it: bridge network, root, files in a named volume.
            docker("volume", "create", volume);
            docker("run", "-d", "--name", container, "--label", "com.codepal.workspace=true",
                    "--mount", "type=volume,source=" + volume + ",target=/workspace", "--workdir", "/workspace",
                    properties.getSandbox().getImage(), "sh", "-lc", "while :; do sleep 3600; done");
            assertEquals("bridge", dockerOutput("container", "inspect", "-f", "{{.HostConfig.NetworkMode}}", container));

            DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
            sandbox.ensure(projectId);

            assertEquals("none|1000:1000|bind=" + LINUX_BASE + "/" + projectId + ";", dockerOutput("container", "inspect", "-f",
                    "{{.HostConfig.NetworkMode}}|{{.Config.User}}|{{range .Mounts}}{{.Type}}={{.Source}};{{end}}", container));
            SandboxPort.CommandResult outbound = sandbox.exec(projectId, List.of("node", "-e",
                    "require('https').get('https://registry.npmjs.org/',r=>{console.log('REACHED',r.statusCode);process.exit(0)})"
                            + ".on('error',e=>{console.log('BLOCKED',e.code);process.exit(3)})"));
            assertEquals(3, outbound.exitCode(), outbound.stdout());
        } finally {
            docker("volume", "rm", "-f", volume);
        }
    }

    @Test
    void startupCheckConfirmsBothSidesSeeTheSameFolderAndRejectsAMismatch() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        sandbox.verifyEnvironment();

        AgentProperties wrong = properties();
        wrong.getSandbox().setHostWorkspacePath("/tmp");    // the daemon would mount a different folder
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> new DockerProjectEnvironment(wrong).verifyEnvironment());
        assertTrue(mismatch.getMessage().contains("not the same folder"), mismatch.getMessage());
    }

    @Test
    void buildsAreStagedArchivedAndCommittedAcrossBackendAndContainer() throws Exception {
        properties.getSandbox().setRequired(true);
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));
        var changes = org.mockito.Mockito.mock(com.code.pal.agent.change.FileChangeRepository.class);
        org.mockito.Mockito.when(changes.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        var service = new com.code.pal.agent.change.ChangeSetService(changes,
                org.mockito.Mockito.mock(com.code.pal.agent.repository.AgentRunRepository.class),
                new tools.jackson.databind.ObjectMapper(), properties, files, sandbox,
                org.mockito.Mockito.mock(com.code.pal.project.service.ProjectService.class));
        UUID projectId = newProject();

        List<UUID> builds = List.of(UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < builds.size(); i++) {
            UUID runId = builds.get(i);
            String stage = service.start(runId, projectId);           // tar copy in the container
            var plan = new com.code.pal.agent.plan.AgentPlanState();
            plan.revise(List.of("Write page", "Validate"), "Initial");
            var context = new com.code.pal.agent.tool.AgentContext(projectId, 7L,
                    com.code.pal.project.domain.ProjectType.BUILD, stage, null,
                    new com.code.pal.agent.context.ProjectContextTrace(), runId, plan, files);
            service.create(context, "page-" + i + ".html", "<h1>" + i + "</h1>");   // written through the share
            service.recordProjectValidation(context, true, List.of());
            service.commit(context, com.code.pal.agent.domain.AgentRun.builder().id(runId).projectId(projectId)
                    .userId(7L).mode(com.code.pal.project.domain.ProjectType.BUILD).build());
        }

        String history = sandbox.exec(projectId, List.of("ls", "/workspace/.codepal/history")).stdout().trim();
        assertEquals(builds.get(1).toString(), history, "older build archives must be pruned");
        assertEquals("<h1>0</h1>", sandbox.exec(projectId, List.of("cat", "page-0.html")).stdout());
        assertEquals("<h1>1</h1>", files.readText(projectId, "page-1.html"));
        String staging = sandbox.exec(projectId, List.of("sh", "-c", "ls /workspace/.codepal/changesets 2>/dev/null | wc -l")).stdout().trim();
        assertEquals("0", staging, "committed runs leave no staging behind");
        assertEquals("1000:1000", sandbox.exec(projectId, List.of("stat", "-c", "%u:%g", "page-1.html")).stdout().trim());

        // Rollback of the latest build restores the project as it was before that build.
        sandbox.restoreTree(projectId, com.code.pal.agent.change.ChangeSetService.archiveFor("archive:" + builds.get(1)));
        assertTrue(files.regularFile(projectId, ".", "page-0.html"));
        assertTrue(!files.exists(projectId, ".", "page-1.html"));
    }

    @Test
    void dependenciesInstallFromTheRegistryWithoutScriptsAndWorkOfflineThroughALink() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));
        var dependencies = new com.code.pal.agent.stack.DependencyService(
                new com.code.pal.agent.stack.StackPacks(new tools.jackson.databind.ObjectMapper()), files, sandbox, properties);
        UUID projectId = newProject();
        sandbox.ensure(projectId);
        // A lifecycle script that would prove package code ran during the install.
        files.writeText(projectId, "package.json", """
                {"name":"it","version":"1.0.0","dependencies":{"is-number":"7.0.0"},
                 "scripts":{"postinstall":"node -e \\"require('fs').writeFileSync('/workspace/POSTINSTALL_RAN','x')\\""}}
                """);

        var first = dependencies.prepare(projectId, ".");
        var second = dependencies.prepare(projectId, ".");

        assertTrue(first.installed(), String.valueOf(first.error()));
        assertTrue(!second.installed() && !second.failed(), "the same manifests reuse the installed set");
        assertTrue(!files.exists(projectId, ".", "POSTINSTALL_RAN"), "install must not run lifecycle scripts");
        // The project container has no network, yet the linked dependency resolves.
        assertEquals("true", sandbox.exec(projectId, List.of("node", "-e", "console.log(require('is-number')(5))")).stdout().trim());
        assertEquals("link", sandbox.exec(projectId, List.of("sh", "-c", "test -L node_modules && echo link")).stdout().trim());
        assertEquals("1000:1000", sandbox.exec(projectId, List.of("sh", "-c", "stat -L -c %u:%g node_modules/is-number")).stdout().trim());
        // Tree copies (staging, validation copies, history) leave dependencies out.
        sandbox.copyTree(projectId, ".", ".codepal/changesets/it/workspace");
        assertEquals("absent", sandbox.exec(projectId, List.of("sh", "-c",
                "test -e .codepal/changesets/it/workspace/node_modules && echo present || echo absent")).stdout().trim());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pnpm@9.12.0", "yarn@1.22.22", "yarn@4.5.0"})
    void pnpmAndYarnProjectsInstallWithTheirOwnManagerFlatAndWithoutScripts(String manager) throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));
        var dependencies = new com.code.pal.agent.stack.DependencyService(
                new com.code.pal.agent.stack.StackPacks(new tools.jackson.databind.ObjectMapper()), files, sandbox, properties);
        UUID projectId = newProject();
        sandbox.ensure(projectId);
        files.writeText(projectId, "package.json", """
                {"name":"it","version":"1.0.0","packageManager":"%s","dependencies":{"is-number":"7.0.0"},
                 "scripts":{"postinstall":"node -e \\"require('fs').writeFileSync('/workspace/POSTINSTALL_RAN','x')\\""}}
                """.formatted(manager));

        var installed = dependencies.prepare(projectId, ".");

        assertTrue(installed.installed(), manager + ": " + installed.error());
        assertTrue(!files.exists(projectId, ".", "POSTINSTALL_RAN"), "install must not run lifecycle scripts");
        // Offline, through the link, from a real (not symlinked-store) folder: like npm's layout.
        assertEquals("true", sandbox.exec(projectId, List.of("node", "-e", "console.log(require('is-number')(5))")).stdout().trim());
        assertEquals("dir", sandbox.exec(projectId, List.of("sh", "-c", "test -d node_modules/is-number && ! test -L node_modules/is-number && echo dir")).stdout().trim());
    }

    @Test
    void aViteAppStyledWithTailwindInstallsWithoutScriptsAndBuildsOffline() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));
        var stacks = new com.code.pal.agent.stack.StackPacks(new tools.jackson.databind.ObjectMapper());
        var dependencies = new com.code.pal.agent.stack.DependencyService(stacks, files, sandbox, properties);
        UUID projectId = newProject();
        sandbox.ensure(projectId);
        files.writeText(projectId, "package.json", """
                {"name":"tw","private":true,"type":"module","devDependencies":{"vite":"7.1.7","tailwindcss":"4.1.14","@tailwindcss/vite":"4.1.14"}}
                """);
        files.writeText(projectId, "vite.config.js", "import { defineConfig } from 'vite';\nimport tailwindcss from '@tailwindcss/vite';\nexport default defineConfig({ plugins: [tailwindcss()] });\n");
        files.writeText(projectId, "index.html", "<!doctype html><html><body><h1 class=\"text-3xl font-bold text-indigo-600\">Hi</h1><script type=\"module\" src=\"/src/main.js\"></script></body></html>");
        files.writeText(projectId, "src/main.js", "import './style.css';\n");
        files.writeText(projectId, "src/style.css", "@import \"tailwindcss\";\n");

        var installed = dependencies.prepare(projectId, ".");
        assertTrue(installed.installed(), String.valueOf(installed.error()));
        var build = stacks.detect(files, projectId, ".").previewBuild(files, projectId, ".");
        SandboxPort.CommandResult built = sandbox.exec(projectId, ".", build.command());

        assertEquals(0, built.exitCode(), built.stdout() + built.error());
        String css = sandbox.exec(projectId, List.of("sh", "-c", "cat dist/assets/*.css")).stdout();
        assertTrue(css.contains("text-indigo-600") && css.contains("font-weight"), "Tailwind generated the used utilities offline");
    }

    @Test
    void restoringFromAMissingArchiveLeavesTheProjectUntouched() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        HostProjectFiles files = new HostProjectFiles(new WorkspaceStorage(properties));
        UUID projectId = newProject();
        sandbox.ensure(projectId);
        files.writeText(projectId, "keep.txt", "precious");

        assertThrows(IllegalStateException.class,
                () -> sandbox.restoreTree(projectId, ".codepal/history/" + UUID.randomUUID() + "/original"));

        assertEquals("precious", files.readText(projectId, "keep.txt"));
    }

    @Test
    void destroyingADeletedProjectRemovesItsFolderAndContainer() throws Exception {
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        UUID projectId = newProject();
        Path backendView = new WorkspaceStorage(properties).projectDir(projectId);
        sandbox.ensure(projectId);
        assertEquals(0, sandbox.exec(projectId, List.of("sh", "-c",
                "mkdir -p .codepal/deps/a/node_modules/react src && for i in $(seq 1 500); do echo x > .codepal/deps/a/node_modules/react/f$i.js; done"
                        + " && ln -s .codepal/deps/a/node_modules node_modules && echo app > src/App.tsx")).exitCode());

        sandbox.destroy(projectId);

        assertTrue(Files.notExists(backendView), "project folder removed");
        assertEquals("", dockerOutput("ps", "-aq", "--filter", "name=" + properties.getSandbox().getContainerPrefix() + projectId));
        sandbox.destroy(projectId);   // nothing left: a second sweep is a no-op
    }

    private UUID newProject() {
        UUID projectId = UUID.randomUUID();
        containers.add(new DockerProjectEnvironment(properties).primaryContainerName(projectId));
        return projectId;
    }

    private static AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "wsl.exe"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", "-e docker"));
        properties.getSandbox().setMemoryMb(256);
        properties.getSandbox().setCpus(0.5);
        // The backend's view of the WSL folder; the Docker-side path is derived from it.
        properties.setWorkspaceBasePath("//wsl.localhost/" + DISTRO + LINUX_BASE);
        return properties;
    }

    private String dockerOutput(String... args) throws Exception {
        return run(args).trim();
    }

    private void docker(String... args) throws Exception {
        run(args);
    }

    private String run(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(properties.getSandbox().getDockerCommand());
        String extra = properties.getSandbox().getDockerCommandArgs();
        if (extra != null && !extra.isBlank()) command.addAll(List.of(extra.trim().split("\\s+")));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor(60, TimeUnit.SECONDS);
        return output;
    }
}
