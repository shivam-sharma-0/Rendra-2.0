package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.FileVersions;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/** Backend resolves real npm integrity data, then records staged lock changes through ordinary mutations. */
@Component @RequiredArgsConstructor
public class RefreshDependenciesTool implements AgentTool {
    private final ChangeSetService changes;
    private final DependencyService dependencies;
    @Override public String name() { return "refreshDependencies"; }
    @Override public String description() { return "After changing npm package dependencies, resolve matching lockfiles with lifecycle scripts disabled. Writes only staged package locks; never hand-write lock integrity data. Run final validation afterward."; }
    @Override public boolean mutating() { return true; }
    @Override public Map<String,Object> parametersSchema() { return Map.of("type", "object", "properties", Map.of()); }
    @Override public Object execute(Map<String,Object> args, AgentContext context) {
        changes.view(context); // Refuse an inactive/quarantined stage before spending install time.
        if (!context.plan().exists()) throw new AgentToolException("PLANNING_REQUIRED", "Plan before refreshing dependencies");
        Map<String,String> resolved;
        try { resolved = dependencies.refreshNpmLocks(context.projectId(), context.root()); }
        catch (com.code.pal.agent.llm.LlmCancelledException cancelled) { throw cancelled; }
        catch (RuntimeException error) { throw new AgentToolException("DEPENDENCY_REFRESH_FAILED", "Could not resolve matching npm locks; inspect install diagnostics before retrying"); }
        List<String> updated = new ArrayList<>();
        for (var entry : resolved.entrySet()) {
            if (com.code.pal.agent.runtime.RunCancellation.current().isCancelled()) throw new com.code.pal.agent.llm.LlmCancelledException();
            String path = context.resolvePath(entry.getKey());
            String version = context.files().version(context.projectId(), context.root(), path);
            if (version.equals(FileVersions.ABSENT)) changes.create(context, path, entry.getValue());
            else {
                String before = new String(context.files().readBytes(context.projectId(), context.root(), path, 1_000_000), java.nio.charset.StandardCharsets.UTF_8);
                if (before.equals(entry.getValue())) continue;
                if (before.isEmpty()) { changes.delete(context, path, version); changes.create(context, path, entry.getValue()); }
                else changes.patch(context, path, version, before, entry.getValue());
            }
            updated.add(path);
        }
        return Map.of("createdFiles", List.copyOf(updated), "next", "Re-run final validation with these resolved lockfiles");
    }
}
