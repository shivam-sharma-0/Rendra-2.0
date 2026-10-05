package com.code.pal.agent.validation;

import com.code.pal.agent.workspace.ProjectFiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Resolves fixed validation operations from the original project manifest; no model command text is
 * accepted. Commands are run by the sandbox in the directory they were resolved for.
 */
@Component
public class ProjectCommandResolver {
    private static final List<String> TYPECHECK_SCRIPTS = List.of("typecheck", "type-check", "check:types", "types:check");
    private static final List<String> TEST_SCRIPTS = List.of("test", "test:unit");
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    private final ObjectMapper objectMapper;

    public ProjectCommandResolver(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Capabilities inspect(ProjectFiles files, UUID projectId, String root) {
        if (!files.regularFile(projectId, root, "package.json")) {
            return new Capabilities(null, null, Map.of(), false, "package.json not found");
        }
        try {
            Map<String, Object> manifest = manifest(files, projectId, root);
            Map<String, String> scripts = new LinkedHashMap<>();
            if (manifest.get("scripts") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> { if (key != null && value != null) scripts.put(key.toString(), value.toString()); });
            }
            String declared = manifest.get("packageManager") == null ? null : manifest.get("packageManager").toString();
            String manager = detectManager(files, projectId, root, declared);
            boolean localTypeScript = files.regularFile(projectId, root, "node_modules/typescript/bin/tsc");
            return new Capabilities(manager, declared, Map.copyOf(scripts), localTypeScript, null);
        } catch (RuntimeException error) {
            return new Capabilities(null, null, Map.of(), false, "package.json could not be read");
        }
    }

    public CommandPlan resolve(ProjectFiles files, UUID projectId, String root, ValidationOperation operation, String scope) {
        Capabilities capabilities = inspect(files, projectId, root);
        if (capabilities.error() != null) return CommandPlan.unavailable(operation, capabilities, capabilities.error());
        String script = switch (operation) {
            case TYPE_CHECK -> firstPresent(capabilities.scripts(), TYPECHECK_SCRIPTS);
            case BUILD -> capabilities.scripts().containsKey("build") ? "build" : null;
            case TESTS -> firstPresent(capabilities.scripts(), TEST_SCRIPTS);
            default -> null;
        };
        List<String> command;
        if (script != null) {
            command = new ArrayList<>(List.of(capabilities.packageManager(), "run", script));
            if (scope != null && !scope.isBlank()) { command.add("--"); command.add(scope); }
        } else if (operation == ValidationOperation.TYPE_CHECK && capabilities.localTypeScript()
                && hasTypeScriptConfig(files, projectId, root)) {
            command = List.of("node", "node_modules/typescript/bin/tsc", "--noEmit", "--pretty", "false");
            script = "typescript --noEmit";
        } else {
            return CommandPlan.unavailable(operation, capabilities, "No matching project script/configuration was found");
        }
        return new CommandPlan(operation, true, capabilities.packageManager(), script, List.copyOf(command), null);
    }

    /** Keep command-bearing package scripts identical to the original trusted project during validation. */
    public void preserveOriginalScripts(ProjectFiles files, UUID projectId, String liveRoot, String candidateRoot) {
        if (!files.regularFile(projectId, liveRoot, "package.json") || !files.regularFile(projectId, candidateRoot, "package.json")) return;
        try {
            Map<String, Object> live = manifest(files, projectId, liveRoot);
            Map<String, Object> safe = new LinkedHashMap<>(manifest(files, projectId, candidateRoot));
            if (live.containsKey("scripts")) safe.put("scripts", live.get("scripts"));
            else safe.remove("scripts");
            files.writeText(projectId, candidateRoot, "package.json", objectMapper.writeValueAsString(safe));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not prepare trusted validation manifest", error);
        }
    }

    public boolean validationScriptsChanged(ProjectFiles files, UUID projectId, String liveRoot, String stagedRoot) {
        try {
            return !Objects.equals(manifest(files, projectId, liveRoot).get("scripts"),
                    manifest(files, projectId, stagedRoot).get("scripts"));
        } catch (RuntimeException error) { return false; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> manifest(ProjectFiles files, UUID projectId, String root) {
        byte[] bytes = files.readBytes(projectId, root, "package.json", MAX_MANIFEST_BYTES);
        return objectMapper.readValue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Map.class);
    }

    private static String detectManager(ProjectFiles files, UUID projectId, String root, String declared) {
        if (declared != null && !declared.isBlank()) {
            String name = declared.split("@", 2)[0].toLowerCase(Locale.ROOT);
            if (Set.of("npm", "pnpm", "yarn", "bun").contains(name)) return name;
        }
        if (files.exists(projectId, root, "pnpm-lock.yaml")) return "pnpm";
        if (files.exists(projectId, root, "yarn.lock")) return "yarn";
        if (files.exists(projectId, root, "bun.lock") || files.exists(projectId, root, "bun.lockb")) return "bun";
        return "npm";
    }

    private static boolean hasTypeScriptConfig(ProjectFiles files, UUID projectId, String root) {
        return files.list(projectId, root, 1).stream().anyMatch(entry -> !entry.directory()
                && entry.path().startsWith("tsconfig") && entry.path().endsWith(".json"));
    }
    private static String firstPresent(Map<String, String> scripts, List<String> names) { return names.stream().filter(scripts::containsKey).findFirst().orElse(null); }

    public record Capabilities(String packageManager, String declaredPackageManager, Map<String, String> scripts,
                               boolean localTypeScript, String error) {
        public List<String> availableOperations() {
            List<String> result = new ArrayList<>();
            if (firstPresent(scripts, TYPECHECK_SCRIPTS) != null || localTypeScript) result.add("runTypeCheck");
            if (scripts.containsKey("build")) result.add("runBuild");
            if (firstPresent(scripts, TEST_SCRIPTS) != null) result.add("runTests");
            return List.copyOf(result);
        }
    }
    public record CommandPlan(ValidationOperation operation, boolean available, String packageManager, String script,
                              List<String> command, String unavailableReason) {
        static CommandPlan unavailable(ValidationOperation operation, Capabilities capabilities, String reason) {
            return new CommandPlan(operation, false, capabilities.packageManager(), null, List.of(), reason);
        }
    }
}
