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
            // Declared rather than checked on disk: node_modules is a link the backend does not follow.
            boolean localTypeScript = declaredDependencies(files, projectId, root).contains("typescript");
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

    /**
     * Vite apps are built with a relative base, so the site works under any path prefix (a preview at
     * {@code /preview/<token>/}, a published site at {@code /p/<slug>/}); other projects use their own
     * build script and conventional output folders.
     */
    @Override
    public PreviewBuild previewBuild(ProjectFiles files, UUID projectId, String root) {
        if (!files.regularFile(projectId, root, MANIFEST)) return null;
        if (declaredDependencies(files, projectId, root).contains("vite")) {
            return new PreviewBuild(List.of("node", "node_modules/vite/bin/vite.js", "build", "--base", "./",
                    "--outDir", "dist", "--emptyOutDir"), List.of("dist"));
        }
        StackCapabilities capabilities = inspect(files, projectId, root);
        if (capabilities.scripts().containsKey("build")) {
            return new PreviewBuild(List.of(capabilities.packageManager(), "run", "build"), List.of("dist", "build", "out"));
        }
        return null;
    }

    @Override
    public PreviewBuild diagnosticBuild(ProjectFiles files, UUID projectId, String root) {
        PreviewBuild build = previewBuild(files, projectId, root);
        if (build == null || !build.command().contains("node_modules/vite/bin/vite.js")) return build;
        List<String> readable = new ArrayList<>(build.command());
        readable.addAll(List.of("--minify", "false"));
        return new PreviewBuild(List.copyOf(readable), build.outputDirectories());
    }

    /**
     * Only the fields npm resolves from, key-sorted, plus the exact lockfile: scripts, formatting or a
     * renamed project do not trigger a new install (validation rewrites the candidate's scripts).
     */
    @Override
    public String dependencyFingerprint(ProjectFiles files, UUID projectId, String root) {
        Map<String, Object> relevant = new TreeMap<>();
        try {
            Map<String, Object> manifest = manifest(files, projectId, root);
            for (String field : List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies",
                    "overrides", "packageManager", "engines", "workspaces")) {
                if (manifest.containsKey(field)) relevant.put(field, sorted(manifest.get(field)));
            }
        } catch (RuntimeException unreadable) {
            return StackPack.super.dependencyFingerprint(files, projectId, root);
        }
        return objectMapper.writeValueAsString(relevant) + "\n" + LOCKFILE + "=" + files.version(projectId, root, LOCKFILE);
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sorted(item)));
            return result;
        }
        return value;
    }

    @Override
    public String dependencyDirectory() {
        return "node_modules";
    }

    /** TypeScript check and Vite build straight from the installed packages, when the project declares them. */
    @Override
    public CommandPlan resolveFixed(ProjectFiles files, UUID projectId, String stagedRoot, ValidationOperation operation) {
        StackCapabilities capabilities = inspect(files, projectId, stagedRoot);
        if (capabilities.error() != null) return CommandPlan.unavailable(operation, capabilities, capabilities.error());
        Set<String> declared = declaredDependencies(files, projectId, stagedRoot);
        List<String> command = switch (operation) {
            case TYPE_CHECK -> declared.contains("typescript") && hasTypeScriptConfig(files, projectId, stagedRoot)
                    ? (usesProjectReferences(files, projectId, stagedRoot)
                        ? List.of("node", "node_modules/typescript/bin/tsc", "-b", "--pretty", "false")
                        : List.of("node", "node_modules/typescript/bin/tsc", "--noEmit", "--pretty", "false"))
                    : null;
            case BUILD -> declared.contains("vite") ? List.of("node", "node_modules/vite/bin/vite.js", "build") : null;
            default -> null;
        };
        if (command == null) return CommandPlan.unavailable(operation, capabilities, "No fixed command for this project");
        return new CommandPlan(operation, true, "npm", String.join(" ", command.subList(1, command.size())), command, null);
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
        return !declaredDependencies(files, projectId, root).isEmpty();
    }

    private Set<String> declaredDependencies(ProjectFiles files, UUID projectId, String root) {
        try {
            Map<String, Object> manifest = manifest(files, projectId, root);
            Set<String> names = new TreeSet<>();
            for (String section : List.of("dependencies", "devDependencies")) {
                if (manifest.get(section) instanceof Map<?, ?> deps) deps.keySet().forEach(name -> names.add(String.valueOf(name)));
            }
            return names;
        } catch (RuntimeException unreadable) {
            return Set.of();
        }
    }

    /** Vite's TypeScript templates split configs with {@code references}; {@code tsc -b} checks all of them. */
    private static boolean usesProjectReferences(ProjectFiles files, UUID projectId, String root) {
        try {
            return files.regularFile(projectId, root, "tsconfig.json")
                    && files.readText(projectId, root, "tsconfig.json").contains("\"references\"");
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
