package com.code.pal.agent.tool;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.validation.RunBehaviorCriteria;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.domain.ProjectType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Removes only operations proved unavailable now. Mode authority and final validation remain separate gates. */
@Service
public final class CapabilityToolInventory {
    private static final Set<String> COMMAND_TOOLS = Set.of("runTypeCheck", "runBuild", "runTests");
    private static final List<String> MANIFESTS = List.of("package.json", "client/package.json", "server/package.json");
    private final AgentProperties properties;
    private final StackPacks stacks;
    private final ProjectFiles files;
    private final RunBehaviorCriteria criteria;

    public CapabilityToolInventory(AgentProperties properties, StackPacks stacks, ProjectFiles files, RunBehaviorCriteria criteria) {
        this.properties = properties;
        this.stacks = stacks;
        this.files = files;
        this.criteria = criteria;
    }

    public List<AgentTool> select(List<AgentTool> modeAllowed, AgentContext context) {
        if (!properties.isCompactToolCatalog() || context.mode() != ProjectType.BUILD) return List.copyOf(modeAllowed);
        boolean hasManifest = MANIFESTS.stream().anyMatch(path -> files.exists(context.projectId(), context.root(), path));
        var capabilities = hasManifest ? stacks.detect(files, context.projectId(), context.root()).inspect(files, context.projectId(), context.root()) : null;
        boolean commandsKnown = capabilities != null && capabilities.error() == null;
        boolean canRetire = true;
        if (modeAllowed.stream().anyMatch(tool -> tool.name().equals("retireAcceptanceCheck"))) {
            var own = criteria.current(context);
            var inherited = own == null ? criteria.regression(context) : null;
            canRetire = inherited != null && inherited.modelSpec();
        }
        boolean retirementAvailable = canRetire;
        return modeAllowed.stream().filter(tool -> {
            if (tool.name().equals("startProject") && hasManifest) return false;
            if (tool.name().equals("retireAcceptanceCheck") && !retirementAvailable) return false;
            return !commandsKnown || !COMMAND_TOOLS.contains(tool.name()) || capabilities.availableOperations().contains(tool.name());
        }).toList();
    }
}
