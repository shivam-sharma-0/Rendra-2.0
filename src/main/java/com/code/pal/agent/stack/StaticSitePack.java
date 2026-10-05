package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.workspace.ProjectFiles;

import java.util.UUID;

/**
 * Plain HTML/CSS/JS served as is: no dependencies, no build. Also the fallback for projects no other
 * pack recognizes (including an empty project before the first build), so validation is structural only.
 */
public class StaticSitePack implements StackPack {

    @Override
    public String id() {
        return "static-site";
    }

    @Override
    public int detect(ProjectFiles files, UUID projectId, String root) {
        return files.regularFile(projectId, root, "index.html") ? 1 : 0;
    }

    @Override
    public StackCapabilities inspect(ProjectFiles files, UUID projectId, String root) {
        return StackCapabilities.unavailable(id(), "Static site: no package.json, nothing to install or build");
    }

    @Override
    public CommandPlan resolve(ProjectFiles files, UUID projectId, String root, ValidationOperation operation, String scope) {
        return CommandPlan.unavailable(operation, inspect(files, projectId, root), "A static site has no " + operation.name().toLowerCase() + " step");
    }
}
