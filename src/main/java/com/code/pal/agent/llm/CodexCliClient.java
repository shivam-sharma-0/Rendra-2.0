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
final class CodexCliClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_PROTOCOL = """
            You may call the tools listed under ### tools. Reply with exactly one JSON object and nothing else:
            to call tools: {"tool_calls":[{"name":"<tool name>","arguments":{<arguments per its parameters schema>}}]}
            to answer without calling a tool: {"final":"<your reply text>"}
            Each ### tool section is the result of an earlier call, in order.
            """;

    private CodexCliClient() {}

    static LlmClient.LlmResult run(LlmClient.LlmRequest request, AgentProperties.Dev dev) {
        if (!dev.isCodexEnabled()) throw new LlmException("The developer Codex route is disabled");
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("rendra-codex-");
            Path last = directory.resolve("last-message.txt");
            var command = new ArrayList<>(List.of(dev.getCodexCommand(), "exec", "--json", "--skip-git-repo-check", "--sandbox",
                    "read-only", "--ephemeral", "--ignore-rules", "--color", "never", "-C", directory.toString(), "-o",
                    last.toString(), "-c", "model_reasoning_effort=\"" + effort(dev) + "\""));
            if (request.model() != null && !request.model().isBlank() && !request.model().equals("default"))
                command.addAll(List.of("-m", request.model()));
            command.add("-");
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("codex.log").toFile()).start();
            String prompt = prompt(request);
            try (var stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            long started = System.nanoTime();
            long deadline = started + Duration.ofSeconds(Math.max(30, dev.getCodexTimeoutSeconds())).toNanos();
            log.info("codex_call_started model={} effort={} promptChars={} tools={}", request.model(), effort(dev),
                    prompt.length(), request.tools() == null ? 0 : request.tools().size());
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                if (RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted())
                    throw new LlmCancelledException();
                if (System.nanoTime() > deadline) {
                    log.warn("codex_call_timed_out model={} afterMs={}", request.model(), (System.nanoTime() - started) / 1_000_000);
                    throw new LlmException("The developer Codex route timed out");
                }
            }
            var usage = usage(directory.resolve("codex.log"));
            log.info("codex_call_finished model={} exit={} elapsedMs={} inputTokens={} cachedTokens={} outputTokens={}", request.model(),
                    process.exitValue(), (System.nanoTime() - started) / 1_000_000, usage == null ? null : usage.promptTokens(),
                    usage == null ? null : usage.cachedInputTokens(), usage == null ? null : usage.completionTokens());
            if (process.exitValue() != 0 || !Files.isRegularFile(last))
                throw new LlmException("The developer Codex route failed with exit " + process.exitValue()
                        + failure(directory.resolve("codex.log")));
            var result = parse(Files.readString(last, StandardCharsets.UTF_8).strip(), request.tools() != null && !request.tools().isEmpty());
            return new LlmClient.LlmResult(result.content(), result.reasoning(), result.toolCalls(), usage, result.finishReason());
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
        boolean tools = request.tools() != null && !request.tools().isEmpty();
        var out = new StringBuilder(tools
                ? "Act as the tool-calling model of a coding agent: run no commands and edit no files yourself.\n" + TOOL_PROTOCOL
                : "Answer as a JSON API: return only the final JSON response, run no commands and edit no files.\n");
        if (tools) {
            out.append("\n### tools\n");
            for (var tool : request.tools())
                out.append(JSON.writeValueAsString(Map.of("name", tool.name(), "description", tool.description() == null ? "" : tool.description(),
                        "parameters", tool.parameters() == null ? Map.of() : tool.parameters()))).append("\n");
        }
        for (var message : request.messages()) {
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                var calls = new ArrayList<Map<String, Object>>();
                for (var call : message.toolCalls())
                    calls.add(Map.of("id", call.id(), "name", call.name(), "arguments", arguments(call.arguments())));
                out.append("\n### assistant\n").append(JSON.writeValueAsString(Map.of("tool_calls", calls))).append("\n");
                continue;
            }
            out.append("\n### ").append(message.role());
            if (message.toolCallId() != null) out.append(" ").append(message.toolCallId());
            out.append("\n").append(message.content() == null ? "" : message.content()).append("\n");
        }
        return out.toString();
    }

    static LlmClient.LlmResult parse(String text, boolean tools) {
        if (!tools) return new LlmClient.LlmResult(text, null, List.of(), null, "stop");
        JsonNode reply;
        try {
            reply = JSON.readTree(unfenced(text));
        } catch (RuntimeException notJson) {
            return new LlmClient.LlmResult(text, null, List.of(), null, "stop");
        }
        if (reply.path("tool_calls").isArray() && !reply.path("tool_calls").isEmpty()) {
            var calls = new ArrayList<LlmClient.ToolCall>();
            for (var call : reply.path("tool_calls")) {
                String name = call.path("name").asString("");
                if (name.isBlank()) throw new LlmException("The Codex route returned a tool call without a name");
                JsonNode arguments = call.path("arguments");
                calls.add(new LlmClient.ToolCall("codex-" + (calls.size() + 1), name,
                        arguments.isObject() ? JSON.writeValueAsString(arguments) : arguments.isString() ? arguments.asString() : "{}"));
            }
            return new LlmClient.LlmResult(null, null, calls, null, "tool_calls");
        }
        String content = reply.path("final").isString() ? reply.path("final").asString() : text;
        return new LlmClient.LlmResult(content, null, List.of(), null, "stop");
    }

    static String effort(AgentProperties.Dev dev) {
        var purpose = LlmCallContext.current().purpose();
        return purpose == CallPurpose.REQUIREMENTS || purpose == CallPurpose.SMALL_TALK
                ? dev.getCodexLightReasoningEffort() : dev.getCodexReasoningEffort();
    }

    static String failure(Path log) {
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return lines.map(String::strip).map(CodexCliClient::errorText).filter(text -> !text.isEmpty())
                    .reduce((first, second) -> second)
                    .map(text -> ": " + text.substring(0, Math.min(200, text.length()))).orElse("");
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private static String errorText(String line) {
        if (line.startsWith("ERROR:"))
            return line;
        if (!line.startsWith("{") || !line.contains("\"type\":\"error\"") && !line.contains("\"type\":\"turn.failed\""))
            return "";
        try {
            var event = JSON.readTree(line);
            String message = event.path("message").asString(event.path("error").path("message").asString(""));
            return message.isBlank() ? "" : "ERROR: " + message;
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    static LlmClient.Usage usage(Path log) {
        try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith("{") && line.contains("\"type\":\"turn.completed\""))
                    .reduce((first, second) -> second).map(line -> JSON.readTree(line).path("usage"))
                    .filter(JsonNode::isObject)
                    .map(usage -> new LlmClient.Usage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0),
                            usage.path("cached_input_tokens").asInt(0), usage.path("reasoning_output_tokens").asInt(0)))
                    .orElse(null);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Object arguments(String json) {
        try { return JSON.readTree(json == null ? "{}" : json); }
        catch (RuntimeException notJson) { return json; }
    }

    private static String unfenced(String text) {
        String value = text.strip();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            if (newline > 0 && value.endsWith("```")) value = value.substring(newline + 1, value.length() - 3).strip();
        }
        return value;
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
