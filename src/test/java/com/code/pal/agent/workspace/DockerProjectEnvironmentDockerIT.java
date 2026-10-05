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
        String container = properties.getSandbox().getContainerPrefix() + projectId;
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
                new tools.jackson.databind.ObjectMapper(), properties, files, sandbox);
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

    private UUID newProject() {
        UUID projectId = UUID.randomUUID();
        containers.add(properties.getSandbox().getContainerPrefix() + projectId);
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
