package com.code.pal.agent.vision;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.runtime.ReleaseSource;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Advisory visual feedback bound to a probe's exact source and two private, bounded captures. */
@Component
public final class GeneratedVisualReview {
    private static final String ROOT = ".codepal/browser-evidence/current";
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private record Capture(String name, String hash) {}
    private record Evidence(UUID project, String source, List<Capture> captures) {}
    private final Map<UUID, Evidence> evidence = new ConcurrentHashMap<>();
    private final AgentProperties properties;
    private final ProjectFiles files;
    private final ObjectMapper mapper;

    public GeneratedVisualReview(AgentProperties properties, ProjectFiles files, ObjectMapper mapper) {
        this.properties = properties;
        this.files = files;
        this.mapper = mapper;
    }

    public void record(AgentContext context, JsonNode report) {
        evidence.remove(context.runId());
        if (!properties.getVisualReview().isEnabled() || !report.path("visual").path("captures").isArray()) {
            return;
        }
        var captures = new ArrayList<Capture>();
        for (var capture : report.path("visual").path("captures")) {
            String name = capture.path("name").asString();
            String hash = capture.path("sha256").asString();
            if (!List.of("desktop", "mobile").contains(name) || !hash.matches("[a-f0-9]{64}")
                    || captures.stream().anyMatch(previous -> previous.name().equals(name))) {
                return;
            }
            captures.add(new Capture(name, hash));
        }
        if (captures.size() == 2) {
            evidence.put(context.runId(), new Evidence(context.projectId(),
                    ReleaseSource.capture(files, context.projectId(), context.root()), List.copyOf(captures)));
        }
    }

    /** Single-use evidence; the runner accounts this optional call inside its normal limits. */
    public List<String> review(AgentContext context, String request, Function<LlmRequest, LlmResult> checkedCall) {
        return review(context, request, checkedCall, Prompt.VISUAL_REVIEW);
    }

    public List<String> review(AgentContext context, String request, Function<LlmRequest, LlmResult> checkedCall, Prompt prompt) {
        Evidence current = evidence.remove(context.runId());
        var models = properties.visionModels();
        if (!properties.getVisualReview().isEnabled() || current == null || models.isEmpty()
                || !current.project().equals(context.projectId())
                || !current.source().equals(ReleaseSource.capture(files, context.projectId(), context.root()))) {
            return List.of();
        }
        var images = new ArrayList<String>();
        try {
            for (var capture : current.captures()) {
                byte[] bytes = files.readBytes(context.projectId(), ROOT, capture.name() + ".png", MAX_IMAGE_BYTES);
                if (!hash(bytes).equals(capture.hash())) {
                    return List.of();
                }
                String image = VisionService.dataUrl(bytes);
                if (image == null) {
                    return List.of();
                }
                images.add(image);
            }
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return List.of();
        }
        var model = models.getFirst();
        String data = mapper.writeValueAsString(Map.of("request", request, "views", List.of("desktop", "mobile")));
        var settings = properties.getVisualReview();
        var result = checkedCall.apply(new LlmRequest(model.provider(), model.model(), List.of(
                LlmMessage.system(PromptLoader.load(prompt)), LlmMessage.userWithImages(data, images)),
                List.of(), 0, Duration.ofMillis(Math.max(1, settings.getTimeoutMs())), null,
                Math.max(1, settings.getMaxOutputTokens())));
        if (result == null || "length".equals(result.finishReason()) || result.content() == null
                || result.content().length() > 8_000) {
            return List.of();
        }
        try {
            String text = result.content().strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            var issues = mapper.readTree(text).path("issues");
            if (!issues.isArray() || issues.size() > 3) {
                return List.of();
            }
            var feedback = new ArrayList<String>();
            for (var issue : issues) {
                if (!issue.isString() || issue.asString().isBlank() || issue.asString().length() > 500) {
                    return List.of();
                }
                feedback.add(issue.asString());
            }
            return List.copyOf(feedback);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    public void clear(UUID run) {
        evidence.remove(run);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
