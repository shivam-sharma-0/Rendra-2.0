package com.code.pal.agent.contract;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Read-only repair evidence. Manifest input exposes dependency names/versions, never config or secrets. */
public final class RepairProjectContext {
    private RepairProjectContext() {}

    public static Set<String> existing(ProjectFiles files, AgentContext ctx) {
        var paths = new TreeSet<String>();
        files.list(ctx.projectId(), ctx.root(), 32, Set.of("node_modules", ".git", ".codepal", "target", "dist", "build"))
                .stream().filter(entry -> !entry.directory()).forEach(entry -> paths.add(entry.path()));
        return paths;
    }

    public static Map<String, Object> capture(ProjectFiles files, AgentContext ctx, BuildContract contract,
            List<BuildContract.Unit> units, ObjectMapper mapper) {
        var scope = new HashSet<String>();
        units.forEach(unit -> {
            scope.addAll(ContractImports.availablePaths(contract, unit));
            contract.units().stream().filter(dependency -> unit.dependsOn().contains(dependency.id()))
                    .forEach(dependency -> scope.addAll(dependency.ownedPaths()));
        });
        var tree = existing(files, ctx).stream().filter(path -> scope.contains(path)
                || path.startsWith(contract.clientRoot()) || path.startsWith(contract.serverRoot())).toList();
        var packages = new TreeMap<String, Object>();
        for (String path : contract.stack() == BuildContract.Stack.MERN
                ? List.of("client/package.json", "server/package.json") : List.of("package.json")) {
            if (!files.regularFile(ctx.projectId(), ctx.root(), path)) continue;
            var manifest = mapper.readTree(files.readText(ctx.projectId(), ctx.root(), path));
            var dependencies = new TreeMap<String, Object>();
            for (String group : List.of("dependencies", "devDependencies")) {
                if (manifest.path(group).isObject()) dependencies.put(group, manifest.path(group));
            }
            packages.put(path, dependencies);
        }
        return Map.of("fileTree", tree, "packageDependencies", packages);
    }
}
