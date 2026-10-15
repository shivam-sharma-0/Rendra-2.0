package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerProjectEnvironmentTest {

    private static final Duration PAST_IDLE_STOP = Duration.ofMinutes(16);   // default idle stop is 15 min

    @Test
    void concurrentCallersProvisionTheContainerOnlyOnce() throws Exception {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();

        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    sandbox.ensure(projectId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);   // none may throw "Conflict"
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, docker.count("run"));
    }

    @Test
    void readyProjectSkipsDockerChecksOnLaterCalls() {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();

        sandbox.ensure(projectId);
        int afterFirst = docker.calls.size();
        sandbox.ensure(projectId);
        sandbox.ensure(projectId);

        assertEquals(afterFirst, docker.calls.size());
    }

    @Test
    void containerCreatedByAnotherProcessIsAccepted() {
        FakeDocker docker = new FakeDocker();
        docker.containerExists = true;      // exists, but our first inspect reports it missing (lost race)
        docker.running = false;
        docker.hideContainerOnFirstInspect = true;
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());

        sandbox.ensure(UUID.randomUUID());

        assertEquals(1, docker.count("run"));
        assertEquals(1, docker.count("start"));
        assertTrue(docker.running);
    }

    @Test
    void newContainersHaveNoNetwork() {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());

        sandbox.ensure(UUID.randomUUID());

        List<String> run = docker.calls.stream().filter(call -> call.get(0).equals("run")).findFirst().orElseThrow();
        assertEquals("none", run.get(run.indexOf("--network") + 1));
    }

    @Test
    void containerFromAnOlderConfigurationIsRecreatedOnTheProjectFolder() {
        FakeDocker docker = new FakeDocker();
        docker.containerExists = true;
        docker.running = true;
        docker.networkMode = "bridge";            // as created before 0.7 and 1.2b:
        docker.user = "";                         // network access, root,
        docker.mounts = "volume=codepal-project-old;";   // files in a named volume
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();

        sandbox.ensure(projectId);

        assertEquals(1, docker.count("rm"));
        assertEquals(1, docker.count("run"));
        assertEquals("none", docker.networkMode);
        assertEquals("1000:1000", docker.user);
        assertEquals("bind=" + DOCKER_BASE + "/" + projectId + ";", docker.mounts);
        assertTrue(docker.calls.stream().noneMatch(call -> call.get(0).equals("volume")),
                "volumes are neither created nor removed any more");
    }

    @Test
    void aContainerThatAlreadyMatchesIsKept() {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);                // creates it

        DockerProjectEnvironment restarted = sandbox(docker, new MutableClock());
        restarted.ensure(projectId);               // a new process sees the same container

        assertEquals(0, docker.count("rm"));
        assertEquals(1, docker.count("run"));
    }

    @Test
    void stoppedContainerIsRestartedAndTheCommandRetriedOnce() {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);

        docker.running = false;             // e.g. a daemon restart this process did not observe
        CommandResult result = sandbox.exec(projectId, List.of("echo", "hi"));

        assertTrue(result.success());
        assertEquals(1, docker.count("start"));
    }

    @Test
    void commandOutputMentioningTheDaemonErrorIsNotRetried() {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);
        int execsBefore = docker.count("exec");

        docker.execOutput = "build failed\nError response from daemon: container x is not running";
        CommandResult result = sandbox.exec(projectId, List.of("npm", "run", "build"));

        assertFalse(result.success());
        assertEquals(execsBefore + 1, docker.count("exec"));   // ran exactly once, no re-provision
        assertEquals(0, docker.count("start"));
    }

    @Test
    void idleCleanupStopsTheContainerAndTheNextCallStartsItBeforeRunning() {
        FakeDocker docker = new FakeDocker();
        MutableClock clock = new MutableClock();
        DockerProjectEnvironment sandbox = sandbox(docker, clock);
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);

        clock.advance(PAST_IDLE_STOP);
        sandbox.cleanupIdleSandboxes();

        assertEquals(1, docker.count("stop"));
        assertFalse(docker.running);

        CommandResult result = sandbox.exec(projectId, List.of("echo", "hi"));
        assertTrue(result.success());
        assertEquals(1, docker.count("start"));
        assertEquals(0, docker.unavailableExecs, "cleanup must clear ready state, so no failed exec is needed");
    }

    @Test
    void idleCleanupSkipsAProjectWhileACommandIsRunning() throws Exception {
        FakeDocker docker = new FakeDocker();
        MutableClock clock = new MutableClock();
        DockerProjectEnvironment sandbox = sandbox(docker, clock);
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);

        docker.blockExec = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CommandResult> running = pool.submit(() -> sandbox.exec(projectId, List.of("npm", "run", "build")));
            assertTrue(docker.execStarted.await(5, TimeUnit.SECONDS));

            clock.advance(PAST_IDLE_STOP);
            sandbox.cleanupIdleSandboxes();           // must return immediately, not wait for the command
            assertEquals(0, docker.count("stop"));

            docker.blockExec.countDown();
            assertTrue(running.get(5, TimeUnit.SECONDS).success());
        } finally {
            docker.blockExec.countDown();
            pool.shutdownNow();
        }
    }

    private static final String KILL_ALL = "kill -KILL -1 2>/dev/null; true";

    @Test
    void stopRemovesOnlyItsOwnedCommandContainer() throws Exception {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);

        String candidate=".codepal/candidate";
        java.nio.file.Path base=java.nio.file.Path.of(sandboxPath(sandbox));
        java.nio.file.Files.createDirectories(base.resolve(projectId.toString()).resolve(candidate));
        docker.blockExec = new CountDownLatch(1);
        com.code.pal.agent.runtime.RunCancellation run = new com.code.pal.agent.runtime.RunCancellation(UUID.randomUUID());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CommandResult> running = pool.submit(() -> {
                try (var bound = com.code.pal.agent.runtime.RunCancellation.bind(run)) {
                    return sandbox.exec(projectId,candidate,List.of("npm", "run", "build"));
                }
            });
            assertTrue(docker.execStarted.await(5, TimeUnit.SECONDS));
            long started=System.nanoTime();run.cancel();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<200,"Stop hook must return promptly");
            docker.blockExec.countDown();assertTrue(running.get(5,TimeUnit.SECONDS).cancelled());
            var created=docker.calls.stream().filter(call->call.get(0).equals("create")).findFirst().orElseThrow();
            String owned=created.get(created.indexOf("--name")+1);
            assertTrue(docker.calls.stream().anyMatch(call->call.get(0).equals("rm") && call.contains(owned)));
            assertTrue(docker.calls.stream().noneMatch(call->call.contains(KILL_ALL)));
            assertTrue(docker.running,"persistent preview/process container stays alive");
        } finally {
            docker.blockExec.countDown();
            pool.shutdownNow();
        }
    }

    @Test void trustedCopyCannotStartWithoutDurableIoOwnership() {
        FakeDocker docker=new FakeDocker();var sandbox=sandbox(docker,new MutableClock());UUID project=UUID.randomUUID();sandbox.ensure(project);
        var io=org.mockito.Mockito.mock(com.code.pal.agent.lease.TrustedProjectIo.class);sandbox.useTrustedIo(io);
        org.mockito.Mockito.when(io.begin(org.mockito.ArgumentMatchers.eq(project),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->sandbox.copyTree(project,".",".codepal/archive"));
        assertEquals(0,docker.count("create"));
    }
    @Test void uncertainTrustedContainerRemovalDoesNotReleaseItsDurableQuarantine() {
        FakeDocker docker=new FakeDocker();var sandbox=sandbox(docker,new MutableClock());UUID project=UUID.randomUUID();sandbox.ensure(project);
        var io=org.mockito.Mockito.mock(com.code.pal.agent.lease.TrustedProjectIo.class);sandbox.useTrustedIo(io);UUID operation=UUID.randomUUID();
        org.mockito.Mockito.when(io.begin(org.mockito.ArgumentMatchers.eq(project),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(operation);
        docker.failTrustedRemoval=true;
        org.junit.jupiter.api.Assertions.assertThrows(com.code.pal.agent.lease.TrustedOperationPendingException.class,()->sandbox.copyTree(project,".",".codepal/archive"));
        org.mockito.Mockito.verify(io,org.mockito.Mockito.never()).finish(operation);
    }

    @Test
    void aCommandOutsideARunRegistersNoKill() {
        FakeDocker docker = new FakeDocker();
        DockerProjectEnvironment sandbox = sandbox(docker, new MutableClock());
        UUID projectId = UUID.randomUUID();
        sandbox.ensure(projectId);
        assertTrue(sandbox.exec(projectId, List.of("echo", "hi")).success());
        assertTrue(docker.calls.stream().noneMatch(call -> call.contains(KILL_ALL)));
    }

    private static DockerProjectEnvironment sandbox(FakeDocker docker, Clock clock) {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setRequired(true);    // cleanup only runs for a required sandbox
        properties.getSandbox().setHostWorkspacePath(DOCKER_BASE);
        try {
            properties.setWorkspaceBasePath(java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"), "docker-env-").toString());
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        return new DockerProjectEnvironment(properties, new WorkspaceStorage(properties), docker, clock);
    }

    private static String sandboxPath(DockerProjectEnvironment sandbox) throws Exception {
        var field=DockerProjectEnvironment.class.getDeclaredField("storage");field.setAccessible(true);
        return ((WorkspaceStorage)field.get(sandbox)).base().toString();
    }
    private static final String DOCKER_BASE = "/srv/codepal/workspaces";

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-02T10:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Minimal in-memory model of the Docker CLI responses the environment depends on. */
    private static final class FakeDocker implements DockerProjectEnvironment.CommandRunner {
        final List<List<String>> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch execStarted = new CountDownLatch(1);
        volatile boolean failTrustedRemoval;
        volatile boolean volumeExists;
        volatile boolean containerExists;
        volatile boolean running;
        volatile boolean hideContainerOnFirstInspect;
        volatile String networkMode = "none";
        volatile String user = "1000:1000";
        volatile String mounts = "";
        volatile String execOutput;
        volatile CountDownLatch blockExec;
        volatile int unavailableExecs;

        @Override
        public CommandResult run(List<String> args, Duration timeout, byte[] stdin, int outputLimit) {
            calls.add(List.copyOf(args));
            String verb = args.get(0);
            if(verb.equals("info")) return ok("fixture-daemon");
            if(verb.equals("rm") && args.stream().anyMatch(a->a.startsWith("codepal-trusted-")) && failTrustedRemoval) return result(false,"Error: daemon unavailable");
            if (verb.equals("volume")) {
                if (args.get(1).equals("inspect")) return result(volumeExists, "");
                volumeExists = true;
                return ok("");
            }
            if (verb.equals("container") && args.get(1).equals("ls")) return ok("");
            if (verb.equals("container") && args.get(1).equals("inspect")) {
                if (args.contains("{{.State.Running}}")) return ok(Boolean.toString(running));
                if (args.contains("{{.State.Status}}")) {
                    return containerExists ? ok(running ? "running" : "exited") : result(false, "Error: No such container");
                }
                pause();                    // widen the window in which unsynchronized callers would race
                if (hideContainerOnFirstInspect) {
                    hideContainerOnFirstInspect = false;
                    return result(false, "Error: No such container");
                }
                return containerExists ? ok(networkMode + "|" + user + "|" + mounts) : result(false, "Error: No such container");
            }
            if (verb.equals("run")) {
                synchronized (this) {
                    if (containerExists) {
                        return result(false, "docker: Error response from daemon: Conflict. The container name is already in use");
                    }
                    containerExists = true;
                    running = true;
                    networkMode = args.get(args.indexOf("--network") + 1);
                    user = args.get(args.indexOf("--user") + 1);
                    String mount = args.get(args.indexOf("--mount") + 1);   // type=bind,source=X,target=Y
                    mounts = mount.replaceAll("^type=(\\w+),source=([^,]+),.*$", "$1=$2;");
                }
                return ok("container-id");
            }
            if(verb.equals("create")) return ok("owned-id");
            if(verb.equals("inspect") && args.contains("{{.State.ExitCode}}")) return ok("0");
            if(verb.equals("rm") && args.getLast().startsWith("codepal-command-")) return ok("");
            if(verb.equals("start") && args.contains("-a")) {
                execStarted.countDown();
                if(blockExec!=null) try { blockExec.await(5,TimeUnit.SECONDS); } catch(InterruptedException stop) { Thread.currentThread().interrupt(); }
                return ok("");
            }
            if (verb.equals("rm")) {
                containerExists = false;
                running = false;
                return ok("");
            }
            if (verb.equals("start")) {
                running = true;
                return ok("");
            }
            if (verb.equals("stop")) {
                running = false;
                return ok("");
            }
            if (verb.equals("exec")) {
                if (!containerExists || !running) {
                    unavailableExecs++;
                    return result(false, containerExists
                            ? "Error response from daemon: container x is not running"
                            : "Error response from daemon: No such container: x");
                }
                if (args.contains("mkdir")) return ok("");
                if (args.contains(KILL_ALL)) return ok("");
                execStarted.countDown();
                CountDownLatch block = blockExec;
                if (block != null) {
                    try { block.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return execOutput == null ? ok("") : result(false, execOutput);
            }
            return result(false, "unexpected docker call " + args);
        }

        int count(String verb) {
            synchronized (calls) {
                return (int) calls.stream().filter(call -> call.get(0).equals(verb)).count();
            }
        }

        private static CommandResult ok(String stdout) { return new CommandResult(true, 0, stdout, ""); }
        private static CommandResult result(boolean success, String stdout) {
            return new CommandResult(success, success ? 0 : 1, stdout, "");
        }
        private static void pause() {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
