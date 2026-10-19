package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Starts one owned process once; unavailable read-only inspection may retry without repeating its work. */
final class OwnedDockerCommand {
    private final DockerProjectEnvironment.CommandRunner runner;
    private final AgentProperties.Sandbox settings;
    private final Consumer<String> cleanup;
    private final ObjectMapper mapper = new ObjectMapper();

    OwnedDockerCommand(DockerProjectEnvironment.CommandRunner runner, AgentProperties.Sandbox settings,
            Consumer<String> cleanup) {
        this.runner = runner;
        this.settings = settings;
        this.cleanup = cleanup;
    }

    CommandResult execute(String name, List<String> args, Duration timeout, byte[] stdin, int outputLimit) {
        RunCancellation signal = RunCancellation.current();
        if (signal.isCancelled()) return new CommandResult(false, -1, "", CommandResult.CANCELLED);
        List<String> create = new ArrayList<>();
        create.add("create");
        create.addAll(args.subList(args.size() > 1 && args.get(1).equals("--rm") ? 2 : 1, args.size()));
        if (stdin != null) create.add(1, "-i");
        try {
            CommandResult prepared;
            try (var ignored = RunCancellation.bind(RunCancellation.NONE)) {
                prepared = runner.run(create, Duration.ofSeconds(20), null, outputLimit);
            }
            if (!prepared.success()) return prepared;
            try (var hook = signal.onCancel(() -> Thread.ofVirtual().start(() -> cleanup.accept(name)))) {
                if (signal.isCancelled()) return new CommandResult(false, -1, "", CommandResult.CANCELLED);
                var start = stdin == null ? List.of("start", "-a", name) : List.of("start", "-a", "-i", name);
                CommandResult result = runner.run(start, timeout, stdin, outputLimit);
                if (signal.isCancelled()) return new CommandResult(false, -1, result.stdout(), CommandResult.CANCELLED);
                if (result.timedOut() || !result.error().isBlank()) return result;
                return outcome(name, result.stdout(), signal);
            }
        } finally {
            boolean interrupted = Thread.interrupted();
            try { cleanup.accept(name); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private CommandResult outcome(String name, String output, RunCancellation signal) {
        int attempts = Math.max(1, Math.min(3, settings.getCommandStateAttempts()));
        Duration timeout = Duration.ofMillis(Math.max(100, Math.min(30_000, settings.getCommandStateTimeoutMs())));
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (signal.isCancelled()) return new CommandResult(false, -1, output, CommandResult.CANCELLED);
            var state = runner.run(List.of("inspect", "-f", "{{json .State}}", name), timeout, null, 4_096);
            if (signal.isCancelled()) return new CommandResult(false, -1, output, CommandResult.CANCELLED);
            if (!state.success()) continue;
            try {
                var value = mapper.readTree(state.stdout());
                if (!value.isObject() || !value.path("Running").isBoolean() || value.path("Running").asBoolean()
                        || !value.path("Status").asString("").equals("exited") || !value.path("ExitCode").isInt()
                        || !value.path("StartedAt").asString("").matches("[1-9][0-9]{3}-.+")) {
                    return new CommandResult(false, -1, output, "Command termination is unverified");
                }
                int exit = value.path("ExitCode").asInt();
                return new CommandResult(exit == 0, exit, output, "");
            } catch (RuntimeException malformed) {
                return new CommandResult(false, -1, output, "Command exit state invalid");
            }
        }
        return new CommandResult(false, -1, output, "Command exit state unavailable");
    }
}
