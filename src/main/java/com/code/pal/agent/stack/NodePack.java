package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.ProjectFiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Node projects with a {@code package.json} (Vite/React today). Validation runs the project's own
 * typecheck/build/test scripts as they were before the run; dependencies are installed with npm with
 * lifecycle scripts disabled, so no package code runs during the install.
 */
public class NodePack implements StackPack {
    private static final List<String> TYPECHECK_SCRIPTS = List.of("typecheck", "type-check", "check:types", "types:check");
    private static final List<String> TEST_SCRIPTS = List.of("test", "test:unit");
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    static final String MANIFEST = "package.json";
    static final String LOCKFILE = "package-lock.json";

    private final ObjectMapper objectMapper;

    public NodePack(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "node";
    }

    @Override
    public int detect(ProjectFiles files, UUID projectId, String root) {
        return files.regularFile(projectId, root, MANIFEST) ? 10 : 0;
    }

    @Override
    public StackCapabilities inspect(ProjectFiles files, UUID projectId, String root) {
        if (!files.regularFile(projectId, root, MANIFEST)) return StackCapabilities.unavailable(id(), "package.json not found");
        try {
            Map<String, Object> manifest = manifest(files, projectId, root);
            Map<String, String> scripts = new LinkedHashMap<>();
            if (manifest.get("scripts") instanceof Map<?, ?> raw) {
                raw.forEach((key, value) -> { if (key != null && value != null) scripts.put(key.toString(), value.toString()); });
            }
            String declared = manifest.get("packageManager") == null ? null : manifest.get("packageManager").toString();
            boolean localTypeScript = files.regularFile(projectId, root, "node_modules/typescript/bin/tsc");
            return new StackCapabilities(id(), detectManager(files, projectId, root, declared), declared, Map.copyOf(scripts),
                    StackCapabilities.operations(firstPresent(scripts, TYPECHECK_SCRIPTS) != null || localTypeScript,
                            scripts.containsKey("build"), firstPresent(scripts, TEST_SCRIPTS) != null), null);
        } catch (RuntimeException error) {
            return StackCapabilities.unavailable(id(), "package.json could not be read");
        }
    }

    @Override
    public CommandPlan resolve(ProjectFiles files, UUID projectId, String root, ValidationOperation operation, String scope) {
        StackCapabilities capabilities = inspect(files, projectId, root);
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
        } else if (operation == ValidationOperation.TYPE_CHECK && capabilities.availableOperations().contains("runTypeCheck")
                && hasTypeScriptConfig(files, projectId, root)) {
            command = List.of("node", "node_modules/typescript/bin/tsc", "--noEmit", "--pretty", "false");
            script = "typescript --noEmit";
        } else {
            return CommandPlan.unavailable(operation, capabilities, "No matching project script/configuration was found");
        }
        return new CommandPlan(operation, true, capabilities.packageManager(), script, List.copyOf(command), null);
    }

    @Override
    public List<String> dependencyManifests() {
        return List.of(MANIFEST, LOCKFILE);
    }

    /** npm only for now (the sandbox image ships it); other lockfiles are ignored. */
    @Override
    public List<String> installCommand(ProjectFiles files, UUID projectId, String root) {
        if (!files.regularFile(projectId, root, MANIFEST) || !declaresDependencies(files, projectId, root)) return List.of();
        String mode = files.regularFile(projectId, root, LOCKFILE) ? "ci" : "install";
        return List.of("npm", mode, "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error");
    }

    @Override
    public void preserveTrustedCommands(ProjectFiles files, UUID projectId, String liveRoot, String candidateRoot) {
        if (!files.regularFile(projectId, liveRoot, MANIFEST) || !files.regularFile(projectId, candidateRoot, MANIFEST)) return;
        try {
            Map<String, Object> live = manifest(files, projectId, liveRoot);
            Map<String, Object> safe = new LinkedHashMap<>(manifest(files, projectId, candidateRoot));
            if (live.containsKey("scripts")) safe.put("scripts", live.get("scripts"));
            else safe.remove("scripts");
            files.writeText(projectId, candidateRoot, MANIFEST, objectMapper.writeValueAsString(safe));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not prepare trusted validation manifest", error);
        }
    }

    @Override
    public boolean trustedCommandsChanged(ProjectFiles files, UUID projectId, String liveRoot, String stagedRoot) {
        try {
            return !Objects.equals(manifest(files, projectId, liveRoot).get("scripts"),
                    manifest(files, projectId, stagedRoot).get("scripts"));
        } catch (RuntimeException error) { return false; }
    }

    private boolean declaresDependencies(ProjectFiles files, UUID projectId, String root) {
        try {
            Map<String, Object> manifest = manifest(files, projectId, root);
            return (manifest.get("dependencies") instanceof Map<?, ?> deps && !deps.isEmpty())
                    || (manifest.get("devDependencies") instanceof Map<?, ?> dev && !dev.isEmpty());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> manifest(ProjectFiles files, UUID projectId, String root) {
        byte[] bytes = files.readBytes(projectId, root, MANIFEST, MAX_MANIFEST_BYTES);
        return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), Map.class);
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

    private static String firstPresent(Map<String, String> scripts, List<String> names) {
        return names.stream().filter(scripts::containsKey).findFirst().orElse(null);
    }
}
