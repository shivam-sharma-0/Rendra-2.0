package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OwnedDockerCommandTest {
    @Test
    void anUnavailableStateQueryCanRecoverWithoutRepeatingTheCommand() {
        var fixture = new Fixture();
        fixture.states.add(new CommandResult(false, 1, "", "daemon unavailable"));
        fixture.states.add(state("exited", 0));
        var result = fixture.execute();
        assertTrue(result.success());
        assertEquals("compiler output", result.stdout());
        assertEquals(1, fixture.count("start"));
        assertEquals(1, fixture.count("create"));
        assertEquals(2, fixture.count("inspect"));
        assertEquals(1, fixture.cleanup.get());
    }

    @Test
    void exhaustedInspectionCannotInferSuccessFromTheAttachClient() {
        var fixture = new Fixture();
        var result = fixture.execute();
        assertFalse(result.success());
        assertEquals("Command exit state unavailable", result.error());
        assertEquals(3, fixture.count("inspect"));
        assertEquals(1, fixture.count("start"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "created", "paused", "removing"})
    void aZeroExitFieldDoesNotProveAnUnfinishedOrUnstartedCommand(String status) {
        var fixture = new Fixture();
        fixture.states.add(state(status, 0));
        var result = fixture.execute();
        assertFalse(result.success());
        assertEquals("Command termination is unverified", result.error());
    }

    @Test
    void anUnstartedTimestampCannotBecomeSuccessfulExitEvidence() {
        var fixture = new Fixture();
        fixture.states.add(new CommandResult(true, 0, state("exited", 0).stdout().replace("2026", "0001"), ""));
        assertFalse(fixture.execute().success());
    }

    @Test
    void aProvedCompilerFailureKeepsItsRealExitAndOutput() {
        var fixture = new Fixture();
        fixture.states.add(state("exited", 2));
        var result = fixture.execute();
        assertFalse(result.success());
        assertEquals(2, result.exitCode());
        assertEquals("compiler output", result.stdout());
    }

    @Test
    void malformedStateFailsClosedWithoutReexecutingTheCommand() {
        var fixture = new Fixture();
        fixture.states.add(new CommandResult(true, 0, "not JSON", ""));
        assertEquals("Command exit state invalid", fixture.execute().error());
        assertEquals(1, fixture.count("start"));
    }

    @Test
    void stopDuringInspectionReturnsCancellationAndNeverRetries() {
        var fixture = new Fixture();
        var signal = new RunCancellation(UUID.randomUUID());
        fixture.duringInspection = signal::cancel;
        fixture.states.add(state("exited", 0));
        try (var ignored = RunCancellation.bind(signal)) {
            assertTrue(fixture.execute().cancelled());
        }
        assertEquals(1, fixture.count("inspect"));
        assertTrue(fixture.cleanup.get() >= 1);
    }

    private static CommandResult state(String status, int exit) {
        return new CommandResult(true, 0, "{\"Status\":\"" + status + "\",\"Running\":" + status.equals("running")
                + ",\"ExitCode\":" + exit + ",\"StartedAt\":\"2026-10-17T00:00:00Z\"}", "");
    }

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final ArrayDeque<CommandResult> states = new ArrayDeque<>();
        final AtomicInteger cleanup = new AtomicInteger();
        Runnable duringInspection = () -> {};

        CommandResult execute() {
            var command = new OwnedDockerCommand((args, timeout, stdin, limit) -> {
                String verb = args.getFirst();
                calls.add(verb);
                if (verb.equals("inspect")) {
                    duringInspection.run();
                    return states.isEmpty() ? new CommandResult(false, 1, "", "daemon unavailable") : states.removeFirst();
                }
                return new CommandResult(true, 0, verb.equals("create") ? "owned-id" : "compiler output", "");
            }, new AgentProperties.Sandbox(), name -> cleanup.incrementAndGet());
            return command.execute("owned-command", List.of("run", "--name", "owned-command", "node", "--version"),
                    Duration.ofSeconds(1), null, 1_024);
        }

        long count(String verb) {
            return calls.stream().filter(verb::equals).count();
        }
    }
}
