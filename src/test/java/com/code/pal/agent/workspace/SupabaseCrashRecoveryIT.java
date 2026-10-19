package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real killed JVM + Docker resources; lease ownership is controlled here and tested separately against SQL. */
@EnabledIfSystemProperty(named = "codepal.supabase.it", matches = "true")
class SupabaseCrashRecoveryIT {
    private static AgentProperties settings() {
        var properties = new AgentProperties();
        properties.getSupabaseValidation().setEnabled(true);
        properties.getSupabaseValidation().setRecoveryBudgetMs(60_000);
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "docker"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", ""));
        return properties;
    }

    @Test
    void killingTheBackendWorkerLeavesDiscoverableResourcesThatOwnedRecoveryRemoves(@TempDir Path temporary) throws Exception {
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var properties = settings();
        var actual = new DockerSupabaseScratch(properties, new ObjectMapper()).resources();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = temporary.resolve("worker.args");
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n"
                + Worker.class.getName() + "\n" + project + "\n" + run + "\n");
        Path output = temporary.resolve("worker.log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(executable, "-Dcodepal.docker.command=" + properties.getSandbox().getDockerCommand(),
                "-Dcodepal.docker.args=" + properties.getSandbox().getDockerCommandArgs(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (process.isAlive() && !Files.readString(output).contains("READY") && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(process.isAlive() && Files.readString(output).contains("READY"), "Scratch worker did not reach readiness");
            var owned = actual.discover().stream().map(actual::inspect).filter(identity -> identity.project().equals(project)
                    && identity.run().equals(run)).toList();
            assertEquals(1, owned.size(), "The child must create exactly one owned descriptor");
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Owned worker did not exit");
            assertTrue(actual.discover().contains(owned.getFirst().name()), "Crash must leave a real orphan, not a graceful close");
            var resources = spy(actual);
            // Scope the fixture sweep to this killed worker; other projects' active environments are never candidates.
            doReturn(List.of(owned.getFirst().name())).when(resources).discover();
            var leases = mock(ProjectLeaseService.class);
            doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(leases).recovering(eq(project), any(Runnable.class));
            properties.getSupabaseValidation().setEnabled(false);
            new SupabaseScratchRecovery(resources, leases, properties).sweep();
            verify(leases).requireRecovery(project);
            assertFalse(actual.discover().contains(owned.getFirst().name()), "Inactive owned resources must recover after rollout is disabled");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
            for (String name : actual.discover()) {
                var identity = actual.inspect(name);
                if (identity.project().equals(project) && identity.run().equals(run)) assertTrue(actual.remove(identity), "Owned crash fixture cleanup failed");
            }
        }
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            var factory = new DockerSupabaseScratch(settings(), new ObjectMapper());
            try (var scratch = factory.open(UUID.fromString(args[0]), UUID.fromString(args[1]), List.of())) {
                System.out.println("READY");
                System.out.flush();
                Thread.sleep(Duration.ofMinutes(5));
            }
        }
    }
}
