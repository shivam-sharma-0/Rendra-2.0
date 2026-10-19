package com.code.pal.agent.tool;

import com.code.pal.agent.validation.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded activity evidence. Tool/file bodies and arbitrary successful output never enter the timeline. */
public final class ToolAudit {
    private static final int MAX_PATHS = 50;
    private static final int MAX_TEXT = 300;

    private ToolAudit() {}

    public static Map<String, Object> arguments(Map<String, Object> args) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("argumentNames", args.keySet().stream().sorted().toList());
        audit.putAll(paths(args));
        return audit;
    }

    public static Map<String, Object> paths(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("path", "sourcePath", "targetPath")) {
            if (args.get(key) instanceof String value && !value.isBlank()) out.put(key, clip(value));
        }
        if (args.get("files") instanceof List<?> files) {
            List<String> paths = files.stream().filter(Map.class::isInstance).map(file -> ((Map<?, ?>) file).get("path"))
                    .filter(String.class::isInstance).map(path -> clip((String) path)).limit(MAX_PATHS).toList();
            if (!paths.isEmpty()) out.put("paths", paths);
        }
        if (args.get("steps") instanceof List<?> steps) {
            out.put("steps", steps.stream().filter(Objects::nonNull).map(step -> clip(step.toString())).limit(MAX_PATHS).toList());
        }
        return out;
    }

    public static Map<String, Object> result(ToolExecutionResult result, Map<String, Object> args) {
        if (result.success() && result.output() instanceof ValidationReport report) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("success", true);
            audit.put("validationPassed", report.changeValidationPassed());
            audit.put("validationOutcome", report.outcome().name());
            audit.put("operation", report.operation().name());
            audit.put("introducedDiagnosticCount", report.introducedDiagnostics().size());
            audit.put("preExistingDiagnosticCount", report.preExistingDiagnostics().size());
            audit.put("repairAttempts", report.repairAttempts());
            audit.put("repairAttemptsRemaining", report.repairAttemptsRemaining());
            audit.put("introducedDiagnostics", report.introducedDiagnostics());
            audit.put("preExistingDiagnostics", report.preExistingDiagnostics());
            return Map.copyOf(audit);
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("success", result.success());
        if (!result.success()) audit.put("errorCode", result.error().code());
        audit.putAll(paths(args));
        if (result.success() && args.get("stepId") instanceof Number id && args.get("status") instanceof String status) {
            audit.put("stepId", id.intValue());
            audit.put("planStatus", clip(status));
        }
        if (result.success() && result.output() instanceof Map<?, ?> output && output.get("createdFiles") instanceof List<?> created) {
            audit.put("paths", created.stream().filter(String.class::isInstance).map(path -> clip((String) path)).limit(MAX_PATHS).toList());
        }
        return audit;
    }

    private static String clip(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= MAX_TEXT ? trimmed : trimmed.substring(0, MAX_TEXT) + "...";
    }
}
