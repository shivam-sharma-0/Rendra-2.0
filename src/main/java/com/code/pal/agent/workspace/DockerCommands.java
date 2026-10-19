package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Docker CLI transport for backend-selected arguments; container ownership stays with the caller. */
final class DockerCommands implements DockerProjectEnvironment.CommandRunner {
    private final AgentProperties.Sandbox settings;

    DockerCommands(AgentProperties properties) {
        settings = properties.getSandbox();
    }

    @Override
    public CommandResult run(List<String> args, Duration timeout, byte[] stdin, int outputLimit) {
        if (RunCancellation.current().isCancelled()) {
            return new CommandResult(false, -1, "", CommandResult.CANCELLED);
        }
        List<String> command = new ArrayList<>();
        command.add(settings.getDockerCommand());
        String configuredArgs = settings.getDockerCommandArgs();
        if (configuredArgs != null && !configuredArgs.isBlank()) {
            command.addAll(List.of(configuredArgs.trim().split("\\s+")));
        }
        command.addAll(args);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process launched = process;
            // WSL's pipe transport needs EOF even for commands that consume no input.
            try (OutputStream output = process.getOutputStream()) {
                if (stdin != null) {
                    output.write(stdin);
                }
            }
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    launched.getInputStream().transferTo(new BoundedOutputStream(captured, outputLimit));
                } catch (IOException ignored) {
                    // Process cancellation closes the pipe.
                }
            });
            ProcessWaiter.Outcome outcome = ProcessWaiter.await(process, timeout);
            if (outcome != ProcessWaiter.Outcome.FINISHED) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                reader.join(1_000);
                return new CommandResult(false, -1, captured.toString(StandardCharsets.UTF_8),
                        outcome == ProcessWaiter.Outcome.CANCELLED ? CommandResult.CANCELLED : CommandResult.TIMED_OUT);
            }
            reader.join(1_000);
            return new CommandResult(process.exitValue() == 0, process.exitValue(),
                    captured.toString(StandardCharsets.UTF_8), "");
        } catch (IOException failure) {
            return new CommandResult(false, -1, "", CommandResult.NOT_STARTED + " (docker): " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, -1, "", CommandResult.CANCELLED);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    /** Keeps draining the process after the capture limit so verbose output cannot deadlock the command. */
    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate;
        private final int limit;

        private BoundedOutputStream(ByteArrayOutputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = Math.max(1, limit);
        }

        @Override
        public void write(int value) {
            if (delegate.size() < limit) {
                delegate.write(value);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (delegate.size() < limit) {
                delegate.write(bytes, offset, Math.min(length, limit - delegate.size()));
            }
        }
    }
}
