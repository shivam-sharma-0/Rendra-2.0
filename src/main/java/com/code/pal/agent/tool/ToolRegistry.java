package com.code.pal.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central lookup of all {@link AgentTool} beans. Spring injects every tool on the classpath, so
 * adding a tool is just adding a {@code @Component} — no wiring here. Mode-based filtering
 * (which tools BUILD vs CHAT vs PLAN may use) is layered on when the tools land.
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> byName;

    public ToolRegistry(List<AgentTool> tools) {
        this.byName = tools.stream().collect(Collectors.toMap(AgentTool::name, Function.identity()));
    }

    public AgentTool get(String name) {
        return byName.get(name);
    }

    public Collection<AgentTool> all() {
        return byName.values();
    }
}
