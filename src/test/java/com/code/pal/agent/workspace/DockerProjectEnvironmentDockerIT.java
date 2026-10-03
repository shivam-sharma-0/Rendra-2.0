package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs against the real Docker daemon. Opt-in, because it creates (and then removes) a container
 * and volume: {@code mvnw test -Dtest=DockerProjectEnvironmentDockerIT -Dcodepal.docker.it=true}.
 * Defaults to {@code wsl.exe -e docker}; override with -Dcodepal.docker.command / -Dcodepal.docker.args.
 */
@EnabledIfSystemProperty(named = "codepal.docker.it", matches = "true")
class DockerProjectEnvironmentDockerIT {

    @Test
    void concurrentFirstUseOfAProjectDoesNotConflict() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "wsl.exe"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", "-e docker"));
        properties.getSandbox().setMemoryMb(256);
        properties.getSandbox().setCpus(0.5);
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        UUID projectId = UUID.randomUUID();   // no volume or container exists yet: the original race
        String container = properties.getSandbox().getContainerPrefix() + projectId;
        String volume = properties.getSandbox().getVolumePrefix() + projectId;

        int callers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<DockerProjectEnvironment.CommandResult>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return sandbox.exec(projectId, List.of("cat", "/etc/hostname"));
                }));
            }
            start.countDown();
            for (Future<DockerProjectEnvironment.CommandResult> result : results) {
                assertEquals(0, result.get(3, TimeUnit.MINUTES).exitCode());
            }
        } finally {
            pool.shutdownNow();
            docker(properties, "rm", "-f", container);
            docker(properties, "volume", "rm", "-f", volume);
        }
    }

    @Test
    void generatedCodeHasNoNetworkAndOldContainersAreRecreatedKeepingFiles() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "wsl.exe"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", "-e docker"));
        properties.getSandbox().setMemoryMb(256);
        properties.getSandbox().setCpus(0.5);
        UUID projectId = UUID.randomUUID();
        String container = properties.getSandbox().getContainerPrefix() + projectId;
        String volume = properties.getSandbox().getVolumePrefix() + projectId;
        try {
            // A container as the previous code created it: default (bridge) network, project volume.
            docker(properties, "volume", "create", volume);
            docker(properties, "run", "-d", "--name", container, "--label", "com.codepal.workspace=true",
                    "--mount", "type=volume,source=" + volume + ",target=/workspace", "--workdir", "/workspace",
                    properties.getSandbox().getImage(), "sh", "-lc", "echo kept > /workspace/marker.txt; while :; do sleep 3600; done");
            assertEquals("bridge", dockerOutput(properties, "container", "inspect", "-f", "{{.HostConfig.NetworkMode}}", container));

            DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
            sandbox.ensure(projectId);

            assertEquals("none", dockerOutput(properties, "container", "inspect", "-f", "{{.HostConfig.NetworkMode}}", container));
            assertEquals("kept", sandbox.readText(projectId, "marker.txt").trim(), "project files must survive the recreate");

            DockerProjectEnvironment.CommandResult outbound = sandbox.exec(projectId, List.of("node", "-e",
                    "require('https').get('https://registry.npmjs.org/',r=>{console.log('REACHED',r.statusCode);process.exit(0)})"
                            + ".on('error',e=>{console.log('BLOCKED',e.code);process.exit(3)})"));
            assertEquals(3, outbound.exitCode(), outbound.stdout());
        } finally {
            docker(properties, "rm", "-f", container);
            docker(properties, "volume", "rm", "-f", volume);
        }
    }

    @Test
    void onlyTheLatestBuildArchiveIsKept() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "wsl.exe"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", "-e docker"));
        properties.getSandbox().setRequired(true);
        properties.getSandbox().setMemoryMb(256);
        properties.getSandbox().setCpus(0.5);
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties);
        var changes = org.mockito.Mockito.mock(com.code.pal.agent.change.FileChangeRepository.class);
        org.mockito.Mockito.when(changes.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        var service = new com.code.pal.agent.change.ChangeSetService(changes,
                org.mockito.Mockito.mock(com.code.pal.agent.snapshot.WorkspaceSnapshotService.class),
                org.mockito.Mockito.mock(com.code.pal.agent.repository.AgentRunRepository.class),
                new tools.jackson.databind.ObjectMapper(), properties, sandbox);
        UUID projectId = UUID.randomUUID();
        String container = properties.getSandbox().getContainerPrefix() + projectId;
        String volume = properties.getSandbox().getVolumePrefix() + projectId;
        try {
            List<UUID> builds = List.of(UUID.randomUUID(), UUID.randomUUID());
            for (int i = 0; i < builds.size(); i++) {
                UUID runId = builds.get(i);
                java.nio.file.Path stage = service.start(runId, projectId);
                var plan = new com.code.pal.agent.plan.AgentPlanState();
                plan.revise(List.of("Write page", "Validate"), "Initial");
                var context = new com.code.pal.agent.tool.AgentContext(projectId, 7L,
                        com.code.pal.project.domain.ProjectType.BUILD, stage, null,
                        new com.code.pal.agent.context.ProjectContextTrace(), runId, plan, sandbox,
                        ".codepal/changesets/" + runId + "/workspace");
                service.create(context, "page-" + i + ".html", "<h1>" + i + "</h1>");
                service.recordProjectValidation(context, true, List.of());
                service.commit(context, com.code.pal.agent.domain.AgentRun.builder().id(runId).projectId(projectId)
                        .userId(7L).mode(com.code.pal.project.domain.ProjectType.BUILD).build());
            }

            String history = sandbox.exec(projectId, List.of("ls", "/workspace/.codepal/history")).stdout().trim();
            assertEquals(builds.get(1).toString(), history, "older build archives must be pruned");
            assertEquals("<h1>0</h1>", sandbox.readText(projectId, "page-0.html"));
            assertEquals("<h1>1</h1>", sandbox.readText(projectId, "page-1.html"));
            String staging = sandbox.exec(projectId, List.of("sh", "-c", "ls /workspace/.codepal/changesets 2>/dev/null | wc -l")).stdout().trim();
            assertEquals("0", staging, "committed runs leave no staging behind");
        } finally {
            docker(properties, "rm", "-f", container);
            docker(properties, "volume", "rm", "-f", volume);
        }
    }

    private static String dockerOutput(AgentProperties properties, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(properties.getSandbox().getDockerCommand());
        String extra = properties.getSandbox().getDockerCommandArgs();
        if (extra != null && !extra.isBlank()) command.addAll(List.of(extra.trim().split("\\s+")));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor(60, TimeUnit.SECONDS);
        return output;
    }

    private static void docker(AgentProperties properties, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(properties.getSandbox().getDockerCommand());
        String extra = properties.getSandbox().getDockerCommandArgs();
        if (extra != null && !extra.isBlank()) command.addAll(List.of(extra.trim().split("\\s+")));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor(60, TimeUnit.SECONDS);
    }
}
