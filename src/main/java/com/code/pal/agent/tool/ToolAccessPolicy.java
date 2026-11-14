package com.code.pal.agent.tool;

import com.code.pal.project.domain.ProjectType;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Server-owned mode authority. Tool registration/model text cannot grant additional operations. */
public final class ToolAccessPolicy {
    private static final Set<String> READ = Set.of("listFiles", "readFile", "searchCode", "getProjectMetadata", "getFileSummary",
            "getApprovedRequirement", "findProjectFiles");
    private static final Set<String> PLAN = Set.of("setPlan", "updatePlanStep");
    private static final Set<String> BUILD = Set.of("startProject", "createFile", "createFiles", "patchFile", "deleteFile",
            "renameFile", "copyFile", "refreshDependencies", "getDiagnostics", "runTypeCheck", "runBuild", "runTests",
            "validateChanges", "finishBuild", "retireAcceptanceCheck", "browse", "checkApi");

    private ToolAccessPolicy() {}

    public static List<AgentTool> forMode(Collection<AgentTool> registered, ProjectType mode) {
        return registered.stream().filter(tool -> READ.contains(tool.name()) || mode != ProjectType.CHAT && PLAN.contains(tool.name())
                        || mode == ProjectType.BUILD && BUILD.contains(tool.name()))
                .sorted(Comparator.comparing(AgentTool::name)).toList();
    }
}
