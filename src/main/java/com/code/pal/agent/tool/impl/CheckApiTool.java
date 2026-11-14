package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.validation.MernBehaviorCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CheckApiTool implements AgentTool {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final MernBehaviorCheck api;

    @Override
    public String name() {
        return "checkApi";
    }

    @Override
    public String description() {
        return "Send one request to the staged server with a fresh scratch database and return its status and JSON body "
                + "(tokens and passwords hidden). signedIn creates a fixture account first. Use it to confirm an endpoint "
                + "you wrote behaves as intended; each call builds and starts the server, so check what matters.";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "required", List.of("method", "path"), "properties", Map.of(
                "method", Map.of("type", "string", "maxLength", 6, "description", "GET, POST, PUT, PATCH or DELETE"),
                "path", Map.of("type", "string", "maxLength", 450, "description", "Path starting with /api/"),
                "body", Map.of("type", "string", "maxLength", 4000, "description", "Optional JSON request body"),
                "signedIn", Map.of("type", "boolean", "description", "Send the request as a freshly signed-up account")));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        String method = String.valueOf(args.get("method")).strip().toUpperCase(Locale.ROOT);
        String path = String.valueOf(args.get("path")).strip();
        Object body = args.get("body");
        if (!METHODS.contains(method))
            throw new IllegalArgumentException("method must be GET, POST, PUT, PATCH or DELETE");
        if (!path.matches("/api/[A-Za-z0-9_./-]{1,240}(\\?[A-Za-z0-9_.~%=&-]{1,200})?") || path.contains("..") || path.contains("//"))
            throw new IllegalArgumentException("path must be a local /api/ path");
        return api.request(context, method, path, body == null ? null : String.valueOf(body),
                Boolean.TRUE.equals(args.get("signedIn")));
    }
}
