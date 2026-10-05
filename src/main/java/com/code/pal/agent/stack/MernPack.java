package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A MERN project: a React client in {@code client/} (Vite) and an Express API in {@code server/} (Node,
 * usually TypeScript), each a package of its own. Dependencies are installed per package (each gets its
 * own linked node_modules, see {@link #packageRoots}); validation type-checks both and builds the
 * client with fixed tsc/vite commands run from the project root, so diagnostics name project paths
 * ({@code server/src/routes.ts}). Package scripts are never run for validation.
 */
public class MernPack implements StackPack {
    public static final String CLIENT = "client";
    public static final String SERVER = "server";
    private static final String MANIFEST = "package.json";

    private final NodePack node;

    public MernPack(ObjectMapper objectMapper) {
        this.node = new NodePack(objectMapper);
    }

    @Override
    public String id() {
        return "mern";
    }

    /** Outranks a root package.json (often just a dev runner for both packages). */
    @Override
    public int detect(ProjectFiles files, UUID projectId, String root) {
        return files.regularFile(projectId, ProjectPaths.combine(root, CLIENT), MANIFEST) && files.regularFile(projectId, ProjectPaths.combine(root, SERVER), MANIFEST) ? 20 : 0;
    }

    @Override
    public StackCapabilities inspect(ProjectFiles files, UUID projectId, String root) {
        if (detect(files, projectId, root) == 0) return StackCapabilities.unavailable(id(), "client/package.json and server/package.json not found");
        boolean typeCheck = typeChecks(files, projectId, root).size() > 0;
        boolean build = clientUsesVite(files, projectId, root);
        return new StackCapabilities(id(), "npm", null, Map.of(), StackCapabilities.operations(typeCheck, build, false), null);
    }

    @Override
    public CommandPlan resolve(ProjectFiles files, UUID projectId, String root, ValidationOperation operation, String scope) {
        return resolveFixed(files, projectId, root, operation);
    }

    @Override
    public CommandPlan resolveFixed(ProjectFiles files, UUID projectId, String root, ValidationOperation operation) {
        StackCapabilities capabilities = inspect(files, projectId, root);
        if (capabilities.error() != null) return CommandPlan.unavailable(operation, capabilities, capabilities.error());
        List<String> steps = switch (operation) {
            case TYPE_CHECK -> typeChecks(files, projectId, root);
            case BUILD -> {
                List<String> build = new ArrayList<>();
                if (clientUsesVite(files, projectId, root)) build.add(viteBuild());
                // The server's own build (tsc with its tsconfig's outDir), which the runtime starts from.
                if (typeScript(files, projectId, root, SERVER)) build.add(tsc(SERVER) + " -p " + SERVER + " --pretty false");
                yield build;
            }
            default -> List.of();
        };
        if (steps.isEmpty()) return CommandPlan.unavailable(operation, capabilities, "No fixed command for this project");
        // Commands are fixed parts only (no project input). Every step runs even after one fails, so one pass reports the client's and the server's errors.
        String script = "status=0; " + String.join(" || status=1; ", steps) + " || status=1; exit $status";
        return new CommandPlan(operation, true, "npm", String.join(" && ", steps), List.of("sh", "-c", script), null);
    }

    /** Each package has its own dependencies; {@link DependencyService} installs and links them one by one. */
    @Override
    public List<String> packageRoots(ProjectFiles files, UUID projectId, String root) {
        return List.of(CLIENT, SERVER);
    }

    @Override
    public PreviewBuild previewBuild(ProjectFiles files, UUID projectId, String root) {
        if (!clientUsesVite(files, projectId, root)) return null;
        return new PreviewBuild(List.of("node", CLIENT + "/node_modules/vite/bin/vite.js", "build", CLIENT, "--base", "./",
                "--outDir", "dist", "--emptyOutDir"), List.of(CLIENT + "/dist"));
    }

    @Override
    public PreviewBuild diagnosticBuild(ProjectFiles files, UUID projectId, String root) {
        PreviewBuild build = previewBuild(files, projectId, root);
        if (build == null) return null;
        List<String> readable = new ArrayList<>(build.command());
        readable.addAll(List.of("--minify", "false"));
        return new PreviewBuild(List.copyOf(readable), build.outputDirectories());
    }

    @Override
    public void applyBuildVariables(ProjectFiles files, UUID projectId, String root, Map<String, String> variables) {
        node.applyBuildVariables(files, projectId, ProjectPaths.combine(root, CLIENT), variables);
    }

    private List<String> typeChecks(ProjectFiles files, UUID projectId, String root) {
        List<String> checks = new ArrayList<>();
        for (String pkg : List.of(CLIENT, SERVER)) {
            if (!typeScript(files, projectId, root, pkg)) continue;
            checks.add(references(files, projectId, root, pkg)
                    ? tsc(pkg) + " -b " + pkg + " --pretty false"
                    : tsc(pkg) + " -p " + pkg + " --noEmit --pretty false");
        }
        return checks;
    }

    private boolean typeScript(ProjectFiles files, UUID projectId, String root, String pkg) {
        String packageRoot = ProjectPaths.combine(root, pkg);
        return node.declares(files, projectId, packageRoot, "typescript") && files.regularFile(projectId, packageRoot, "tsconfig.json");
    }

    private boolean clientUsesVite(ProjectFiles files, UUID projectId, String root) {
        return node.declares(files, projectId, ProjectPaths.combine(root, CLIENT), "vite");
    }

    private static boolean references(ProjectFiles files, UUID projectId, String root, String pkg) {
        try {
            return files.readText(projectId, ProjectPaths.combine(root, pkg), "tsconfig.json").contains("\"references\"");
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static String tsc(String pkg) {
        return "node " + pkg + "/node_modules/typescript/bin/tsc";
    }

    private static String viteBuild() {
        return "node " + CLIENT + "/node_modules/vite/bin/vite.js build " + CLIENT;
    }
}
