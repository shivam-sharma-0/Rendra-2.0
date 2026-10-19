package com.code.pal.agent.starter;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.brief.ProjectBriefFile;
import com.code.pal.project.spec.ApprovedSpec.RenderStack;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.code.pal.agent.intent.RequestRouter;

/** Select a tested foundation for a fresh approved app; existing projects keep their setup. */
public final class AutomaticStarter {
    private static final Pattern EXPLICIT_STATIC = Pattern.compile(
            "\\b(?:plain html|vanilla|no react|without react|static (?:site|website)|static html)\\b");
    private static final Pattern EXPLICIT_VITE = Pattern.compile("\\b(?:react|vite|typescript|tsx)\\b");

    private AutomaticStarter() {}

    public static Starter select(AgentContext context, BuildDecisions.Choice choice, String request) {
        return select(context, choice, request, null, RenderStack.VITE);
    }

    public static Starter select(AgentContext context, BuildDecisions.Choice choice, String request,
            RenderStack approvedStack, RenderStack defaultStack) {
        if (choice == null || hasAppFiles(context)) return null;
        if (choice.storage() == BuildDecisions.Storage.MERN) return Starter.MERN;
        if (choice.storage() == BuildDecisions.Storage.SUPABASE) return Starter.VITE_REACT;
        String text = RequestRouter.normalize(request);
        if (EXPLICIT_STATIC.matcher(text).find()) return Starter.STATIC_SITE;
        if (EXPLICIT_VITE.matcher(text).find()) return Starter.VITE_REACT;
        RenderStack stack = approvedStack == null ? defaultStack : approvedStack;
        return stack == RenderStack.STATIC ? Starter.STATIC_SITE : Starter.VITE_REACT;
    }

    private static boolean hasAppFiles(AgentContext context) {
        return context.files().list(context.projectId(), context.root(), 32,
                        Set.of(".codepal", ".git", "node_modules", "uploads")).stream()
                .anyMatch(entry -> !entry.directory() && !ProjectBriefFile.privatePath(entry.path())
                        && !entry.path().equals("notes.md"));
    }

    public static Map<String, Object> context(AgentContext context, Starter starter) {
        var bodies = new LinkedHashMap<String, Object>();
        List<String> paths = switch (starter) {
            case MERN -> List.of("client/src/main.tsx", "client/vite.config.ts", "server/src/index.ts");
            case VITE_REACT -> List.of("src/main.tsx", "src/index.css", "vite.config.ts");
            case STATIC_SITE -> List.of("package.json");
        };
        for (String path : paths) {
            bodies.put(path, Map.of("version", context.files().version(context.projectId(), context.root(), path),
                    "content", context.files().readText(context.projectId(), context.root(), path)));
        }
        var existing = new LinkedHashMap<String, String>();
        starter.files(null).keySet().forEach(path -> existing.put(path,
                context.files().version(context.projectId(), context.root(), path)));
        return Map.of("stack", starter.id(), "templateVersion", Starter.TEMPLATE_VERSION,
                "next", starter.next(), "entryFiles", bodies, "preparedVersions", existing);
    }
}
