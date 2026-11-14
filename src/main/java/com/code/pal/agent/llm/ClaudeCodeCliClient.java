package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@lombok.extern.slf4j.Slf4j
final class ClaudeCodeCliClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM = "You are the model behind a coding platform's API. Follow the instructions in the prompt "
            + "exactly and reply only in the format it asks for.";

    private ClaudeCodeCliClient() {}

    static LlmClient.LlmResult run(LlmClient.LlmRequest request, AgentProperties.Dev dev) {
        if (!dev.isClaudeCodeEnabled()) throw new LlmException("The developer Claude Code route is disabled");
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("rendra-claude-");
            Path output = directory.resolve("claude.json");
            String model = request.model() == null || request.model().isBlank() || request.model().equals("default")
                    ? dev.getClaudeCodeModel() : request.model();
            var images = CodexCliClient.imageUrls(request);
            var command = new ArrayList<>(List.of(dev.getClaudeCodeCommand(), "-p", "--tools", "",
                    "--strict-mcp-config", "--no-session-persistence", "--disable-slash-commands", "--setting-sources", "",
                    "--effort", effort(dev), "--model", model, "--system-prompt", SYSTEM));
            command.addAll(images.isEmpty() ? List.of("--output-format", "json")
                    : List.of("--input-format", "stream-json", "--output-format", "stream-json", "--verbose"));
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            String prompt = CodexCliClient.prompt(request);
            try (var stdin = process.getOutputStream()) {
                stdin.write((images.isEmpty() ? prompt : message(prompt, images)).getBytes(StandardCharsets.UTF_8));
            }
            long started = System.nanoTime();
            long deadline = started + Duration.ofSeconds(Math.max(30, dev.getClaudeCodeTimeoutSeconds())).toNanos();
            log.info("claude_code_call_started model={} effort={} promptChars={} tools={}", model, effort(dev), prompt.length(),
                    request.tools() == null ? 0 : request.tools().size());
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted())
                    throw new LlmCancelledException();
                if (System.nanoTime() > deadline) {
                    log.warn("claude_code_call_timed_out model={} afterMs={}", model, (System.nanoTime() - started) / 1_000_000);
                    throw new LlmException("The developer Claude Code route timed out");
                }
            }
            String raw = Files.readString(output, StandardCharsets.UTF_8);
            JsonNode reply = reply(raw);
            var usage = usage(reply);
            log.info("claude_code_call_finished model={} exit={} elapsedMs={} inputTokens={} cachedTokens={} outputTokens={} listCostUsd={}",
                    model, process.exitValue(), (System.nanoTime() - started) / 1_000_000, usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.cachedInputTokens(), usage == null ? null : usage.completionTokens(),
                    reply == null ? null : reply.path("total_cost_usd").asDouble(0));
            if (process.exitValue() != 0 || reply == null || reply.path("is_error").asBoolean(false) || !reply.path("result").isString())
                throw new LlmException("The developer Claude Code route failed with exit " + process.exitValue() + failure(reply, raw));
            var result = CodexCliClient.parse(reply.path("result").asString().strip(), request.tools() != null && !request.tools().isEmpty());
            return new LlmClient.LlmResult(result.content(), result.reasoning(), result.toolCalls(), usage,
                    "max_tokens".equals(reply.path("stop_reason").asString("")) ? "length" : result.finishReason());
        } catch (IOException unavailable) {
            throw new LlmException("The developer Claude Code route could not start", unavailable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmCancelledException();
        } finally {
            if (process != null && process.isAlive()) process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process != null && process.isAlive()) process.destroyForcibly();
            delete(directory);
        }
    }

    static String message(String prompt, List<String> images) {
        var content = new ArrayList<Map<String, Object>>();
        for (String image : images)
            content.add(Map.of("type", "image", "source", Map.of("type", "base64",
                    "media_type", image.substring("data:".length(), image.indexOf(';')), "data", image.substring(image.indexOf(',') + 1))));
        content.add(Map.of("type", "text", "text", prompt));
        return JSON.writeValueAsString(Map.of("type", "user", "message", Map.of("role", "user", "content", content))) + "\n";
    }

    static String effort(AgentProperties.Dev dev) {
        var purpose = LlmCallContext.current().purpose();
        return purpose == CallPurpose.REQUIREMENTS || purpose == CallPurpose.SMALL_TALK
                ? dev.getClaudeCodeLightEffort() : dev.getClaudeCodeEffort();
    }

    static JsonNode reply(String raw) {
        String[] lines = raw.strip().split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].strip();
            if (!line.startsWith("{") || !line.contains("\"type\":\"result\""))
                continue;
            try {
                return JSON.readTree(line);
            } catch (RuntimeException notJson) {
                return null;
            }
        }
        return null;
    }

    static LlmClient.Usage usage(JsonNode reply) {
        if (reply == null || !reply.path("usage").isObject())
            return null;
        var usage = reply.path("usage");
        int fresh = usage.path("input_tokens").asInt(0), written = usage.path("cache_creation_input_tokens").asInt(0),
                read = usage.path("cache_read_input_tokens").asInt(0);
        return new LlmClient.Usage(fresh + written + read, usage.path("output_tokens").asInt(0), read,
                usage.path("output_tokens_details").path("thinking_tokens").asInt(0));
    }

    static String failure(JsonNode reply, String raw) {
        String text = reply == null ? raw.strip() : reply.path("result").asString(reply.path("subtype").asString(""));
        text = text.replaceAll("\\s+", " ").strip();
        return text.isEmpty() ? "" : ": " + text.substring(0, Math.min(200, text.length()));
    }

    private static void delete(Path directory) {
        if (directory == null) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            return;
        }
    }
}
