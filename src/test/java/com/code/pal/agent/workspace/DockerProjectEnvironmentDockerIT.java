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
