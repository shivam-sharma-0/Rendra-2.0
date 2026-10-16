package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import com.code.pal.project.capability.BuildDecisions;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Writes a {@link Starter}'s tested setup files into a new project, so the model never hand-writes the
 * package versions and configuration that decide whether the project builds at all.
 */
@Component
@RequiredArgsConstructor
public class StartProjectTool implements AgentTool {
    private static final List<String> MANIFESTS = List.of("package.json", "client/package.json", "server/package.json");

    private final ChangeSetService changeSets;
    private BuildDecisions buildDecisions;

    @Autowired
    public void setBuildDecisions(BuildDecisions decisions) {
        this.buildDecisions = Objects.requireNonNull(decisions);
    }

    @Override
    public String name() { return "startProject"; }

    @Override
    public String description() {
        return "Set up a new app from a tested starter: \"vite-react\" (Vite + React + TypeScript + Tailwind) or \"mern\" "
                + "(that app in client/ plus an Express + MongoDB API in server/). Writes package.json with versions that work "
                + "together, a tested package-lock.json and runtime metadata, tsconfig.json, vite.config.ts, index.html, src/main.tsx and src/index.css (for mern also "
                + "server/package.json, server/tsconfig.json and server/src/index.ts with /api/health and the database "
                + "connection). Call it once, after setPlan, in a project without package.json; then write src/App.tsx and the "
                + "rest. Never write these setup files yourself; only add a package to package.json when the app needs one.";
    }

    @Override
    public boolean mutating() { return true; }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "stack", Map.of("type", "string", "enum", List.of("vite-react", "mern")),
                        "title", Map.of("type", "string", "description", "The page title, e.g. the app's name")),
                "required", List.of("stack"));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        Object stack = args.get("stack");
        Starter requested = Starter.of(stack == null ? "" : stack.toString())
                .orElseThrow(() -> new IllegalArgumentException("stack must be \"vite-react\" or \"mern\""));
        // The approved storage decides the stack: without approved MERN storage a "mern" request is a web app.
        var state = buildDecisions == null ? null : buildDecisions.state(context.projectId());
        var choice = state == null ? null : state.choice();
        Starter starter = requested == Starter.MERN && choice != null && choice.storage() != BuildDecisions.Storage.MERN
                ? Starter.VITE_REACT : requested;
        if (buildDecisions != null) buildDecisions.requireStarter(context.projectId(), starter.id());
        for (String manifest : MANIFESTS) {
            if (context.files().exists(context.projectId(), context.root(), manifest)) {
                throw new IllegalArgumentException("The project already has " + manifest + ": keep its setup and stack");
            }
        }
        Map<String, String> files = starter.files(args.get("title") == null ? null : args.get("title").toString());
        // Report existing setup clearly; the batch also repeats preflight inside its critical section.
        List<String> existing = files.keySet().stream()
                .filter(path -> context.files().exists(context.projectId(), context.root(), path)).toList();
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException("These files already exist: " + String.join(", ", existing)
                    + ". Delete them first, or keep the existing setup and do not call startProject.");
        }
        List<String> created = changeSets.createBatch(context, files.entrySet().stream()
                .map(entry -> new ChangeSetService.NewFile(entry.getKey(), entry.getValue())).toList())
                .stream().map(ChangeSetService.MutationResult::path).toList();
        String next = starter == requested ? starter.next()
                : "Approved storage is not MERN, so the vite-react starter was set up (no server). " + starter.next();
        return Map.of("stack", starter.id(), "templateVersion", Starter.TEMPLATE_VERSION,
                "createdFiles", List.copyOf(created), "next", next);
    }
}
