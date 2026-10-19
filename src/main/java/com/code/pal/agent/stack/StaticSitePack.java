package com.code.pal.agent.stack;

import com.code.pal.agent.contract.StaticAssets;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.ProjectFiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Legacy HTML has no build. A reviewed v2 marker selects the pinned static CSS compiler;
 * an invalid marker fails closed instead of selecting arbitrary package scripts through NodePack.
 */
public class StaticSitePack implements StackPack {

    @Override
    public String id() {
        return "static-site";
    }

    @Override
    public void prepareBuild(ProjectFiles files, UUID projectId, String root) {
        if (files.exists(projectId, root, StaticAssets.MARKER)) StaticAssets.prepare(files, projectId, root);
    }

    @Override
    public int detect(ProjectFiles files, UUID projectId, String root) {
        if (files.exists(projectId, root, StaticAssets.MARKER)) {
            return 30;
        }
        return files.regularFile(projectId, root, "index.html") ? 1 : 0;
    }

    @Override
    public StackCapabilities inspect(ProjectFiles files, UUID projectId, String root) {
        if (files.exists(projectId, root, StaticAssets.MARKER)) {
            try {
                StaticAssets.require(files, projectId, root);
                return new StackCapabilities(id(), "npm", "npm@10.9.9", Map.of(), List.of("runBuild"), null);
            } catch (IllegalArgumentException invalid) {
                return StackCapabilities.unavailable(id(), invalid.getMessage());
            }
        }
        return StackCapabilities.unavailable(id(), "Static site: no package.json, nothing to install or build");
    }

    @Override
    public CommandPlan resolve(ProjectFiles files, UUID projectId, String root, ValidationOperation operation, String scope) {
        var capabilities = inspect(files, projectId, root);
        if (operation == ValidationOperation.BUILD && capabilities.error() == null) {
            return new CommandPlan(operation, true, "npm", "trusted static CSS compilation", StaticAssets.command(), null);
        }
        return CommandPlan.unavailable(operation, capabilities, "Static operation is unavailable: " + operation);
    }

    @Override
    public CommandPlan resolveFixed(ProjectFiles files, UUID projectId, String root, ValidationOperation operation) {
        return resolve(files, projectId, root, operation, null);
    }

    @Override
    public PreviewBuild previewBuild(ProjectFiles files, UUID projectId, String root) {
        if (!files.exists(projectId, root, StaticAssets.MARKER)) {
            return null;
        }
        StaticAssets.require(files, projectId, root);
        return new PreviewBuild(StaticAssets.command(), List.of("dist"));
    }

    @Override
    public List<String> dependencyManifests() {
        return List.of("package.json", "package-lock.json");
    }

    @Override
    public String dependencyDirectory() {
        return "node_modules";
    }

    @Override
    public List<String> installCommand(ProjectFiles files, UUID projectId, String root) {
        if (!files.exists(projectId, root, StaticAssets.MARKER)) {
            return List.of();
        }
        StaticAssets.require(files, projectId, root);
        return List.of("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund", "--loglevel=error");
    }
}
