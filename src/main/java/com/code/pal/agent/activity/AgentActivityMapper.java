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
 * <p>Unknown status titles are internal by default: a new user-facing status must be added here, or be emitted as a
 * structured build stage ({@code {"progress":true}} data), which shows as its own clipped title.
 */
@Component
public class AgentActivityMapper {

    private static final int MAX_PATHS = 50;
    private static final int MAX_TEXT = 300;
    private static final Set<String> CHECK_TOOLS = Set.of("runTypeCheck", "runBuild", "runTests", "validateChanges", "getDiagnostics");
    private static final Set<String> PUBLIC_TEXT = Set.of("path","sourcePath","targetPath","status","message","errorCode","operation","planStatus","validationOutcome");
    private static final Set<String> PUBLIC_ARRAYS = Set.of("paths","files","changedFiles","steps");
    private static final Set<String> PUBLIC_BOOLEANS = Set.of("success","validationPassed");
    private static final Set<String> PUBLIC_COUNTS = Set.of("introducedDiagnosticCount","preExistingDiagnosticCount","repairAttempts","repairAttemptsRemaining","stepId");
    private static final Set<String> CHANGE_TOOLS = Set.of("createFile","createFiles","patchFile","deleteFile","renameFile","copyFile","startProject","refreshDependencies","setPlan");

    private final ObjectMapper objectMapper;

    public AgentActivityMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @param dataJson the step's stored JSON data, or null */
    public Optional<AgentActivity> map(AgentStepType type, String content, String dataJson) {
        if (type == null) return Optional.empty();
        JsonNode data = parse(dataJson);
        return Optional.ofNullable(switch (type) {
            case THINKING -> activity(Kind.STATUS, "Reviewing the request", List.of(), null, null);
            case TOOL_CALL -> toolCall(content);
            case TOOL_RESULT -> toolResult(content, data);
            case STATUS -> status(content, data);
            case ASK_USER -> activity(Kind.ASK_USER, text(content, "Waiting for your answer"), List.of(), null, null);
            case MESSAGE -> activity(Kind.MESSAGE, text(content, "Message"), List.of(), null, null);
        });
    }

    /**
     * A user-facing step's data without run telemetry (counters, provider and model names, argument
     * names); null when nothing is left. Only explicitly typed public fields remain; nested payloads and reasoning are omitted.
     */
    public String publicData(String dataJson) {
        JsonNode data = parse(dataJson);
        if (!data.isObject()) return null;
        ObjectNode copy = objectMapper.createObjectNode();
        for(String key:PUBLIC_TEXT)if(data.path(key).isString()) {
            String value=data.path(key).asString();int max=MAX_TEXT;
            copy.put(key,value.substring(0,Math.min(value.length(),max)));
        }
        for(String key:PUBLIC_ARRAYS)if(data.path(key).isArray()) {
            var array=copy.putArray(key);for(String value:strings(data.path(key)))array.add(value);
        }
        for(String key:PUBLIC_BOOLEANS)if(data.path(key).isBoolean())copy.set(key,data.path(key));
        for(String key:PUBLIC_COUNTS)if(data.path(key).isIntegralNumber() && data.path(key).asLong()>=0)copy.set(key,data.path(key));
        return copy.isEmpty() ? null : objectMapper.writeValueAsString(copy);
    }
    /** Old persisted thinking text may contain raw provider reasoning; only the public action is replayed. */
    public String publicContent(AgentStepType type,String content,AgentActivity activity) {
        return type==AgentStepType.THINKING?activity.title():content;
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
            if("SKIPPED".equals(data.path("validationOutcome").asString()))return activity(Kind.CHECK,"Required check could not run",List.of(),false,null);
            return activity(Kind.CHECK, checkDone(tool), List.of(), passed, problems(data));
        }
        if(tool==null)return null;
        if(!ok)return CHANGE_TOOLS.contains(tool)
                ? activity(Kind.STATUS,"Change attempt failed",paths(data),false,"Checking before retrying") : null;
        List<String> paths = paths(data);
        return switch (tool) {
            case "readFile", "getFileSummary" -> activity(Kind.READ, paths.isEmpty() ? "Read a file" : "Read", paths, true, null);
            case "listFiles" -> activity(Kind.READ, "Looked through the project files", List.of(), true, null);
            case "searchCode", "findProjectFiles" -> activity(Kind.READ, "Searched the code", List.of(), true, null);
            case "getApprovedRequirement" -> activity(Kind.READ, "Read the approved requirements", List.of(), true, null);
            case "getProjectMetadata" -> activity(Kind.READ, "Read the project settings", List.of(), true, null);
            case "createFile", "createFiles" -> activity(Kind.CREATE, "Created", paths, true, null);
            case "startProject" -> activity(Kind.CREATE, "Set up the project", paths, true, null);
            case "refreshDependencies" -> activity(Kind.CREATE, "Updated dependencies", paths, true, null);
            case "patchFile" -> activity(Kind.EDIT, "Edited", paths, true, null);
            case "deleteFile" -> activity(Kind.DELETE, "Deleted", paths, true, null);
            case "renameFile" -> activity(Kind.MOVE, "Renamed", paths, true, null);
            case "copyFile" -> activity(Kind.MOVE, "Copied", paths, true, null);
            case "retireAcceptanceCheck" -> activity(Kind.PLAN, "Retired a check for a feature you changed", List.of(), true, null);
            case "setPlan" -> plan(data);
            case "updatePlanStep" -> data.path("stepId").isIntegralNumber() && data.path("planStatus").isString()
                    ? activity(Kind.PLAN,"Updated plan step "+data.path("stepId").asInt(),List.of(),null,
                        "COMPLETED".equals(data.path("planStatus").asString())?"Completion reported; verification required":clip(data.path("planStatus"))) : null;
            default -> null;   // updatePlanStep and anything new: bookkeeping, not progress
        };
    }

    private static AgentActivity status(String title, JsonNode data) {
        if (title == null) return null;
        return switch (title) {
            case "Queued" -> activity(Kind.STATUS, "Waiting to start", List.of(), null, null);
            case "LLM provider unavailable" -> activity(Kind.STATUS,"AI service unavailable; trying another configured option",List.of(),null,null);
            case "Saving changes" -> activity(Kind.STATUS,"Saving changes",List.of(),null,null);
            case "Save recovery pending" -> activity(Kind.STATUS,"Checking an interrupted save",List.of(),false,null);
            case "Reading attachments" -> activity(Kind.ATTACHMENTS, "Looking at the attached images", strings(data.path("files")), null, null);
            case "Final validation" -> activity(Kind.CHECK, "SKIPPED".equals(data.path("validationOutcome").asString())?"Required check could not run":"Checked the app", List.of(),
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
            case "Agent stopped: run budget" -> activity(Kind.FAILED,"This run reached its AI budget",List.of(),false,clip(data.path("message")));
            case "Agent validation failed", "Agent stopped: daily quota" ->
                    activity(Kind.FAILED, title.startsWith("Agent stopped") ? "Daily AI limit reached" : "The changes did not pass the checks",
                            List.of(), false, clip(data.path("message")));
            case "Workspace restored to the snapshot before this run." -> activity(Kind.STATUS, "Restored the version before this run", List.of(), true, null);
            case "Run recovered after worker interruption; workspace restored." ->
                    activity(Kind.STATUS, "Interrupted — the workspace was restored", List.of(), false, null);
            case "Run recovered after worker interruption." -> activity(Kind.STATUS, "Interrupted", List.of(), false, null);
            // Agent started / completed, Model called, LLM provider unavailable, Project context prepared / usage
            default -> data.path("progress").asBoolean(false) && !title.isBlank()
                    ? activity(Kind.PROGRESS, clipTitle(title), List.of(), null, null) : null;
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

    /** A build stage line: one line, at most 120 characters (unit objectives come from the plan). */
    private static String clipTitle(String value) {
        String line = value.strip().replaceAll("\\s+", " ");
        return line.length() <= 120 ? line : line.substring(0, 120) + "…";
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
