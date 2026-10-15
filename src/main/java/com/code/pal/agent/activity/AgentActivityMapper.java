package com.code.pal.agent.activity;

import com.code.pal.agent.activity.AgentActivity.Kind;
import com.code.pal.agent.domain.AgentStepType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a stored timeline step into what the user sees, or nothing for internal steps (model calls,
 * provider fallbacks, context budgets, counters). The UI and the live stream show only mapped steps;
 * every step is still stored for logs, recovery and analysis. Works on old steps too, so earlier runs
 * read the same way (they may lack file paths, which were not recorded before).
 *
 * <p>Unknown status titles are internal by default: a new user-facing status must be added here.
 */
@Component
public class AgentActivityMapper {

    private static final int MAX_PATHS = 50;
    private static final int MAX_TEXT = 300;
    private static final Set<String> CHECK_TOOLS = Set.of("runTypeCheck", "runBuild", "runTests", "validateChanges", "getDiagnostics");
    /** Run telemetry that stays out of what the UI receives. */
    private static final Set<String> INTERNAL_KEYS = Set.of("iteration", "iterations", "modelCall", "modelCalls", "toolCalls",
            "toolCallCount", "contextChars", "remainingContextChars", "compactedChars", "phase", "provider", "model", "retryable", "argumentNames");

    private final ObjectMapper objectMapper;

    public AgentActivityMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @param dataJson the step's stored JSON data, or null */
    public Optional<AgentActivity> map(AgentStepType type, String content, String dataJson) {
        if (type == null) return Optional.empty();
        JsonNode data = parse(dataJson);
        return Optional.ofNullable(switch (type) {
            case THINKING -> activity(Kind.THINKING, "Thinking", List.of(), null, null);
            case TOOL_CALL -> toolCall(content);
            case TOOL_RESULT -> toolResult(content, data);
            case STATUS -> status(content, data);
            case ASK_USER -> activity(Kind.ASK_USER, text(content, "Waiting for your answer"), List.of(), null, null);
            case MESSAGE -> activity(Kind.MESSAGE, text(content, "Message"), List.of(), null, null);
        });
    }

    /**
     * A user-facing step's data without run telemetry (counters, provider and model names, argument
     * names); null when nothing is left. Reasoning, file paths, changed files and diagnostics stay.
     */
    public String publicData(String dataJson) {
        JsonNode data = parse(dataJson);
        if (!(data instanceof ObjectNode object)) return data.isMissingNode() || data.isNull() || data.isEmpty() ? null : dataJson;
        ObjectNode copy = object.deepCopy();
        INTERNAL_KEYS.forEach(copy::remove);
        return copy.isEmpty() ? null : objectMapper.writeValueAsString(copy);
    }

    /** Only slow checks show while they run; every other tool shows once, with its result. */
    private static AgentActivity toolCall(String tool) {
        if (!CHECK_TOOLS.contains(tool)) return null;
        return activity(Kind.CHECK, checkRunning(tool), List.of(), null, null);
    }

    private static AgentActivity toolResult(String tool, JsonNode data) {
        boolean ok = data.path("success").asBoolean(false);
        if (CHECK_TOOLS.contains(tool)) {
            boolean passed = data.has("validationPassed") ? data.path("validationPassed").asBoolean(ok) : ok;
            return activity(Kind.CHECK, checkDone(tool), List.of(), passed, problems(data));
        }
        // A failed file or plan tool is the model's to retry; the user sees the attempt that worked.
        if (!ok || tool == null) return null;
        List<String> paths = paths(data);
        return switch (tool) {
            case "readFile", "getFileSummary" -> activity(Kind.READ, paths.isEmpty() ? "Read a file" : "Read", paths, true, null);
            case "listFiles" -> activity(Kind.READ, "Looked through the project files", List.of(), true, null);
            case "searchCode" -> activity(Kind.READ, "Searched the code", List.of(), true, null);
            case "getProjectMetadata" -> activity(Kind.READ, "Read the project settings", List.of(), true, null);
            case "createFile", "createFiles" -> activity(Kind.CREATE, "Created", paths, true, null);
            case "startProject" -> activity(Kind.CREATE, "Set up the project", paths, true, null);
            case "patchFile" -> activity(Kind.EDIT, "Edited", paths, true, null);
            case "deleteFile" -> activity(Kind.DELETE, "Deleted", paths, true, null);
            case "renameFile" -> activity(Kind.MOVE, "Renamed", paths, true, null);
            case "copyFile" -> activity(Kind.MOVE, "Copied", paths, true, null);
            case "setPlan" -> plan(data);
            default -> null;   // updatePlanStep and anything new: bookkeeping, not progress
        };
    }

    private static AgentActivity status(String title, JsonNode data) {
        if (title == null) return null;
        return switch (title) {
            case "Queued" -> activity(Kind.STATUS, "Waiting to start", List.of(), null, null);
            case "Reading attachments" -> activity(Kind.ATTACHMENTS, "Looking at the attached images", strings(data.path("files")), null, null);
            case "Final validation" -> activity(Kind.CHECK, "Checked the app", List.of(),
                    data.path("validationPassed").asBoolean(false), problems(data));
            case "Polish check" -> activity(Kind.REVIEW, "Reviewing the first version", List.of(), null, null);
            case "Change set committed" -> {
                List<String> files = strings(data.path("changedFiles"));
                yield activity(Kind.SAVED, files.size() == 1 ? "Saved 1 file" : "Saved " + files.size() + " files", files, true, null);
            }
            case "Preview built" -> activity(Kind.PREVIEW, "Preview updated", List.of(), true, null);
            case "Preview build failed" -> activity(Kind.PREVIEW, "The preview could not be built", List.of(), false, null);
            case "Agent cancelled" -> activity(Kind.STOPPED, "Stopped", List.of(), false, null);
            case "Agent failed" -> activity(Kind.FAILED, "Something went wrong", List.of(), false, null);
            case "Agent validation failed", "Agent stopped: daily quota" ->
                    activity(Kind.FAILED, title.startsWith("Agent stopped") ? "Daily AI limit reached" : "The changes did not pass the checks",
                            List.of(), false, clip(data.path("message")));
            case "Workspace restored to the snapshot before this run." -> activity(Kind.STATUS, "Restored the version before this run", List.of(), true, null);
            case "Run recovered after worker interruption; workspace restored." ->
                    activity(Kind.STATUS, "Interrupted — the workspace was restored", List.of(), false, null);
            case "Run recovered after worker interruption." -> activity(Kind.STATUS, "Interrupted", List.of(), false, null);
            // Agent started / completed, Model called, LLM provider unavailable, Project context prepared / usage
            default -> null;
        };
    }

    private static AgentActivity plan(JsonNode data) {
        List<String> steps = strings(data.path("steps"));
        String title = steps.isEmpty() ? "Made a plan" : steps.size() == 1 ? "Made a plan with 1 step" : "Made a plan with " + steps.size() + " steps";
        return new AgentActivity(Kind.PLAN, title, List.of(), true, null, steps);
    }

    private static String checkRunning(String tool) {
        return switch (tool) {
            case "runTypeCheck" -> "Checking types…";
            case "runBuild" -> "Building the app…";
            case "runTests" -> "Running tests…";
            case "validateChanges" -> "Validating the changes…";
            default -> "Checking for problems…";
        };
    }

    private static String checkDone(String tool) {
        return switch (tool) {
            case "runTypeCheck" -> "Checked types";
            case "runBuild" -> "Built the app";
            case "runTests" -> "Ran tests";
            case "validateChanges" -> "Validated the changes";
            default -> "Checked for problems";
        };
    }

    /** "2 problems" when a check introduced diagnostics. */
    private static String problems(JsonNode data) {
        int count = data.path("introducedDiagnosticCount").asInt(0);
        if (count <= 0) return null;
        return count == 1 ? "1 problem" : count + " problems";
    }

    /** `path`, `sourcePath` → `targetPath`, or `paths` as recorded by the runner. */
    private static List<String> paths(JsonNode data) {
        List<String> out = new ArrayList<>();
        for (String field : List.of("path", "sourcePath", "targetPath")) {
            JsonNode node = data.path(field);
            if (node.isValueNode() && !node.isNull() && !node.asText().isBlank()) out.add(clipText(node.asText()));
        }
        out.addAll(strings(data.path("paths")));
        return out.stream().distinct().limit(MAX_PATHS).toList();
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            if (item.isValueNode() && !item.isNull() && !item.asText().isBlank()) out.add(clipText(item.asText()));
            if (out.size() >= MAX_PATHS) break;
        }
        return out;
    }

    private static String clip(JsonNode node) {
        return node.isValueNode() && !node.isNull() && !node.asText().isBlank() ? clipText(node.asText()) : null;
    }

    private static String clipText(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= MAX_TEXT ? trimmed : trimmed.substring(0, MAX_TEXT) + "…";
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : clipText(value);
    }

    private static AgentActivity activity(Kind kind, String title, List<String> paths, Boolean ok, String detail) {
        return new AgentActivity(kind, title, paths, ok, detail, List.of());
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (RuntimeException e) {
            return objectMapper.createObjectNode();
        }
    }
}
