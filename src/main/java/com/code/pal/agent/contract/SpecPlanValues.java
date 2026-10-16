package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.project.spec.ApprovedSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Server-derived values shared by deterministic layouts and model-proposed implementation units. */
final class SpecPlanValues {
    record PageFile(String pageId, String route, String path) {
    }

    private SpecPlanValues() {
    }

    static List<PageFile> pages(ApprovedSpec approved) {
        var pages = approved.spec().pages().stream().sorted(Comparator.comparing(ApprovedSpec.Page::path)).toList();
        if (pages.stream().noneMatch(page -> page.path().equals("/"))) {
            throw new IllegalArgumentException("Approved pages need a homepage route");
        }
        var result = new ArrayList<PageFile>();
        for (var page : pages) {
            String path;
            if (page.path().contains(":")) {
                throw new IllegalArgumentException("Dynamic routes need a supported router plan");
            }
            if (approved.desired().renderStack() == ApprovedSpec.RenderStack.STATIC) {
                String route = page.path().replaceFirst("/+$", "");
                path = route.isEmpty() ? "index.html" : route.substring(1);
                if (!path.endsWith(".html")) {
                    path += "/index.html";
                }
            } else {
                path = pages.size() == 1 ? "src/App.tsx" : "src/pages/" + page.id() + ".tsx";
            }
            BuildContract.canonical(path);
            result.add(new PageFile(page.id(), page.path(), path));
        }
        if (result.stream().map(page -> page.path().toLowerCase(Locale.ROOT)).distinct().count() != result.size()) {
            throw new IllegalArgumentException("Approved routes collide on the same page file");
        }
        return List.copyOf(result);
    }

    static BehaviorCriteria acceptance(ApprovedSpec approved) {
        return SpecAcceptance.compile(approved).criteria();
    }

    static Map<String, String> theme(ApprovedSpec approved) {
        var theme = approved.spec().theme();
        return Map.of("background", theme.background(), "foreground", theme.foreground(), "accent", theme.accent(),
                "muted", theme.muted(), "radius", theme.radius(), "spacing", theme.spacing(), "font", theme.font());
    }

    static List<BuildContract.DataType> types(ApprovedSpec approved) {
        return approved.spec().entities().stream().map(entity -> new BuildContract.DataType(entity.id(),
                entity.fields().stream().map(field -> new BuildContract.Field(field.name(),
                        BuildContract.Kind.valueOf(field.kind()), !field.required())).toList())).toList();
    }
}
