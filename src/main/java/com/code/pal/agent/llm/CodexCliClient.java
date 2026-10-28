package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Developer-only model route through the operator's own logged-in Codex CLI, to measure how a larger model behaves in
 * the pipeline. Disabled unless {@code app.agent.dev.codex-enabled} is set; never a production or multi-user route.
 * Each call runs {@code codex exec} read-only in an empty temporary directory with the prompt on stdin, keeps only the
 * final message, and reports no token usage (the CLI exposes only a combined total).
 */
@lombok.extern.slf4j.Slf4j
final class CodexCliClient {
    private CodexCliClient() {}

    static LlmClient.LlmResult run(LlmClient.LlmRequest request, AgentProperties.Dev dev) {
        if (!dev.isCodexEnabled()) throw new LlmException("The developer Codex route is disabled");
        if (request.tools() != null && !request.tools().isEmpty())
            throw new LlmException("The developer Codex route serves tool-free JSON calls only");
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("rendra-codex-");
            Path last = directory.resolve("last-message.txt");
            var command = new ArrayList<>(List.of(dev.getCodexCommand(), "exec", "--skip-git-repo-check", "--sandbox",
                    "read-only", "--ephemeral", "--ignore-rules", "--color", "never", "-C", directory.toString(), "-o",
                    last.toString(), "-c", "model_reasoning_effort=\"" + dev.getCodexReasoningEffort() + "\""));
            if (request.model() != null && !request.model().isBlank() && !request.model().equals("default"))
                command.addAll(List.of("-m", request.model()));
            command.add("-");
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("codex.log").toFile()).start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(prompt(request).getBytes(StandardCharsets.UTF_8));
            }
            long started = System.nanoTime();
            Duration limit = Duration.ofSeconds(Math.max(30, dev.getCodexTimeoutSeconds()));
            if (request.budget() != null && request.budget().compareTo(limit) < 0) limit = request.budget();
            long deadline = started + limit.toNanos();
            log.info("codex_call_started model={} effort={} promptChars={}", request.model(), dev.getCodexReasoningEffort(),
                    prompt(request).length());
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted())
                    throw new LlmCancelledException();
                if (System.nanoTime() > deadline) {
                    log.warn("codex_call_timed_out model={} afterMs={}", request.model(), (System.nanoTime() - started) / 1_000_000);
                    throw new LlmException("The developer Codex route timed out");
                }
            }
            log.info("codex_call_finished model={} exit={} elapsedMs={}", request.model(), process.exitValue(),
                    (System.nanoTime() - started) / 1_000_000);
            if (process.exitValue() != 0 || !Files.isRegularFile(last))
                throw new LlmException("The developer Codex route failed with exit " + process.exitValue());
            return new LlmClient.LlmResult(Files.readString(last, StandardCharsets.UTF_8).strip(), null, List.of(), null, "stop");
        } catch (IOException unavailable) {
            throw new LlmException("The developer Codex route could not start", unavailable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmCancelledException();
        } finally {
            if (process != null && process.isAlive()) process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process != null && process.isAlive()) process.destroyForcibly();
            delete(directory);
        }
    }

    /** The CLI takes one prompt, so roles become labelled sections in their original order. */
    static String prompt(LlmClient.LlmRequest request) {
        var out = new StringBuilder("Answer as a JSON API: return only the final JSON response, run no commands and edit no files.\n");
        for (var message : request.messages())
            out.append("\n### ").append(message.role()).append("\n").append(message.content() == null ? "" : message.content()).append("\n");
        return out.toString();
    }

    private static void delete(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A leftover empty temp directory is harmless.
        }
    }
}
