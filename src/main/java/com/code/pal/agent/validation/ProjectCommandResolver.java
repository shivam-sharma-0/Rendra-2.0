package com.code.pal.agent.validation;

import com.code.pal.agent.workspace.DockerProjectEnvironment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.UUID;

/** Resolves fixed validation operations from the original project manifest; no model command text is accepted. */
@Component
public class ProjectCommandResolver {
    private static final List<String> TYPECHECK_SCRIPTS = List.of("typecheck", "type-check", "check:types", "types:check");
    private static final List<String> TEST_SCRIPTS = List.of("test", "test:unit");
    private final ObjectMapper objectMapper;

    public ProjectCommandResolver(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public Capabilities inspect(Path liveRoot) {
        Path manifestPath = liveRoot.resolve("package.json");
        if (!Files.isRegularFile(manifestPath)) return new Capabilities(null, null, Map.of(), false, "package.json not found");
        try {
            @SuppressWarnings("unchecked") Map<String, Object> manifest = objectMapper.readValue(Files.readString(manifestPath), Map.class);
            Map<String, String> scripts = new LinkedHashMap<>();
            if (manifest.get("scripts") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> { if (key != null && value != null) scripts.put(key.toString(), value.toString()); });
            }
            String declared = manifest.get("packageManager") == null ? null : manifest.get("packageManager").toString();
            String manager = detectManager(liveRoot, declared);
            boolean localTypeScript = Files.isRegularFile(liveRoot.resolve("node_modules/typescript/bin/tsc"));
            return new Capabilities(manager, declared, Map.copyOf(scripts), localTypeScript, null);
        } catch (IOException | RuntimeException error) {
            return new Capabilities(null, null, Map.of(), false, "package.json could not be read");
        }
    }

    public CommandPlan resolve(Path liveRoot, ValidationOperation operation, String scope) {
        Capabilities capabilities = inspect(liveRoot);
        if (capabilities.error() != null) return CommandPlan.unavailable(operation, capabilities, capabilities.error());
        String script = switch (operation) {
            case TYPE_CHECK -> firstPresent(capabilities.scripts(), TYPECHECK_SCRIPTS);
            case BUILD -> capabilities.scripts().containsKey("build") ? "build" : null;
            case TESTS -> firstPresent(capabilities.scripts(), TEST_SCRIPTS);
            default -> null;
        };
        List<String> command;
        if (script != null) {
            command = packageScriptCommand(capabilities.packageManager(), script, scope);
        } else if (operation == ValidationOperation.TYPE_CHECK && capabilities.localTypeScript()
                && hasTypeScriptConfig(liveRoot)) {
            command = List.of("node", liveRoot.resolve("node_modules/typescript/bin/tsc").toString(), "--noEmit", "--pretty", "false");
            script = "typescript --noEmit";
        } else {
            return CommandPlan.unavailable(operation, capabilities, "No matching project script/configuration was found");
        }
        return new CommandPlan(operation, true, capabilities.packageManager(), script, List.copyOf(command), null);
    }

    public Capabilities inspectSandbox(DockerProjectEnvironment sandbox, UUID projectId, String root) {
        try {
            String manifestText = sandbox.readText(projectId, root, "package.json");
            @SuppressWarnings("unchecked") Map<String, Object> manifest = objectMapper.readValue(manifestText, Map.class);
            Map<String, String> scripts = new LinkedHashMap<>();
            if (manifest.get("scripts") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> { if (key != null && value != null) scripts.put(key.toString(), value.toString()); });
            }
            String declared = manifest.get("packageManager") == null ? null : manifest.get("packageManager").toString();
            String manager = detectSandboxManager(sandbox, projectId, root, declared);
            boolean localTypeScript = sandbox.regularFile(projectId, root, "node_modules/typescript/bin/tsc");
            return new Capabilities(manager, declared, Map.copyOf(scripts), localTypeScript, null);
        } catch (RuntimeException error) {
            return new Capabilities(null, null, Map.of(), false, "package.json could not be read");
        }
    }

    public CommandPlan resolveSandbox(DockerProjectEnvironment sandbox, UUID projectId, String root,
                                      ValidationOperation operation, String scope) {
        Capabilities capabilities = inspectSandbox(sandbox, projectId, root);
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
                && hasTypeScriptConfig(sandbox, projectId, root)) {
            command = List.of("node", "node_modules/typescript/bin/tsc", "--noEmit", "--pretty", "false");
            script = "typescript --noEmit";
        } else {
            return CommandPlan.unavailable(operation, capabilities, "No matching project script/configuration was found");
        }
        return new CommandPlan(operation, true, capabilities.packageManager(), script, List.copyOf(command), null);
    }

    /** Keep command-bearing package scripts identical to the original trusted project during validation. */
    public void preserveOriginalScripts(Path liveRoot, Path candidateRoot) {
        Path liveManifest = liveRoot.resolve("package.json");
        Path candidateManifest = candidateRoot.resolve("package.json");
        if (!Files.isRegularFile(liveManifest) || !Files.isRegularFile(candidateManifest)) return;
        try {
            @SuppressWarnings("unchecked") Map<String, Object> live = objectMapper.readValue(Files.readString(liveManifest), Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> candidate = objectMapper.readValue(Files.readString(candidateManifest), Map.class);
            Map<String, Object> safe = new LinkedHashMap<>(candidate);
            if (live.containsKey("scripts")) safe.put("scripts", live.get("scripts"));
            else safe.remove("scripts");
            Files.writeString(candidateManifest, objectMapper.writeValueAsString(safe));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Could not prepare trusted validation manifest", error);
        }
    }

    public boolean validationScriptsChanged(Path liveRoot, Path stagedRoot) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> live = objectMapper.readValue(Files.readString(liveRoot.resolve("package.json")), Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> staged = objectMapper.readValue(Files.readString(stagedRoot.resolve("package.json")), Map.class);
            return !Objects.equals(live.get("scripts"), staged.get("scripts"));
        } catch (IOException | RuntimeException error) { return false; }
    }

    public void preserveOriginalScripts(DockerProjectEnvironment sandbox, UUID projectId,
                                        String liveRoot, String candidateRoot) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> live = objectMapper.readValue(
                    sandbox.readText(projectId, liveRoot, "package.json"), Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> candidate = objectMapper.readValue(
                    sandbox.readText(projectId, candidateRoot, "package.json"), Map.class);
            Map<String, Object> safe = new LinkedHashMap<>(candidate);
            if (live.containsKey("scripts")) safe.put("scripts", live.get("scripts"));
            else safe.remove("scripts");
            sandbox.writeText(projectId, candidateRoot, "package.json", objectMapper.writeValueAsString(safe));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not prepare trusted validation manifest", error);
        }
    }

    public boolean validationScriptsChanged(DockerProjectEnvironment sandbox, UUID projectId,
                                            String liveRoot, String stagedRoot) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> live = objectMapper.readValue(
                    sandbox.readText(projectId, liveRoot, "package.json"), Map.class);
            @SuppressWarnings("unchecked") Map<String, Object> staged = objectMapper.readValue(
                    sandbox.readText(projectId, stagedRoot, "package.json"), Map.class);
            return !Objects.equals(live.get("scripts"), staged.get("scripts"));
        } catch (RuntimeException error) { return false; }
    }

    private static List<String> packageScriptCommand(String manager, String script, String scope) {
        String executable = isWindows() ? manager + (manager.equals("bun") ? ".exe" : ".cmd") : manager;
        List<String> args = new ArrayList<>(List.of(executable, "run", script));
        if (scope != null && !scope.isBlank()) { args.add("--"); args.add(scope); }
        if (isWindows()) {
            // cmd is needed to launch .cmd package-manager shims. Every token is selected by trusted
            // backend code; scope is separately restricted and no model command string is accepted.
            List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/d", "/s", "/c"));
            wrapped.addAll(args);
            return wrapped;
        }
        return args;
    }

    private static String detectManager(Path root, String declared) {
        if (declared != null && !declared.isBlank()) {
            String name = declared.split("@", 2)[0].toLowerCase(Locale.ROOT);
            if (Set.of("npm", "pnpm", "yarn", "bun").contains(name)) return name;
        }
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) return "pnpm";
        if (Files.exists(root.resolve("yarn.lock"))) return "yarn";
        if (Files.exists(root.resolve("bun.lock")) || Files.exists(root.resolve("bun.lockb"))) return "bun";
        return "npm";
    }

    private static String detectSandboxManager(DockerProjectEnvironment sandbox, UUID projectId, String root, String declared) {
        if (declared != null && !declared.isBlank()) {
            String name = declared.split("@", 2)[0].toLowerCase(Locale.ROOT);
            if (Set.of("npm", "pnpm", "yarn", "bun").contains(name)) return name;
        }
        if (sandbox.exists(projectId, root, "pnpm-lock.yaml")) return "pnpm";
        if (sandbox.exists(projectId, root, "yarn.lock")) return "yarn";
        if (sandbox.exists(projectId, root, "bun.lock") || sandbox.exists(projectId, root, "bun.lockb")) return "bun";
        return "npm";
    }

    private static boolean hasTypeScriptConfig(Path root) {
        try (var files = Files.list(root)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith("tsconfig")
                    && path.getFileName().toString().endsWith(".json"));
        } catch (IOException ignored) { return false; }
    }
    private static boolean hasTypeScriptConfig(DockerProjectEnvironment sandbox, UUID projectId, String root) {
        return sandbox.list(projectId, root, 1).stream().anyMatch(entry -> !entry.directory()
                && entry.path().startsWith("tsconfig") && entry.path().endsWith(".json"));
    }
    private static String firstPresent(Map<String, String> scripts, List<String> names) { return names.stream().filter(scripts::containsKey).findFirst().orElse(null); }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

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
