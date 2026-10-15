package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read a text file from the project workspace. Guards against binaries, secrets and oversize files. */
@Component
public class ReadFileTool implements AgentTool {

    private static final int MAX_BYTES = 24_000; // keep one read compact enough for the model context

    @Override
    public String name() {
        return "readFile";
    }

    @Override
    public String description() {
        return "Read the contents of a text file at the given path (relative to the project root).";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "File path relative to project root")),
                "required", List.of("path"));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        String requested = str(args.get("path"));
        String path = context.resolvePath(requested);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (isSecretFile(name)) {
            throw new IllegalArgumentException("Credential-like files are not available to agent tools");
        }
        var files = context.files();
        if (!files.regularFile(context.projectId(), context.root(), path)) {
            throw new IllegalArgumentException("File not found: " + requested);
        }
        long size = files.size(context.projectId(), context.root(), path);
        String version = files.version(context.projectId(), context.root(), path);
        if (context.contextTrace().wasReadUnchanged(path, size, version)) {
            return Map.of("path", path, "version", version, "unchanged", true,
                    "message", "Unchanged file already present in this run's conversation");
        }

        // Read at most one byte over the cap, so a large repository file never lands in the heap whole.
        byte[] bytes = files.readHead(context.projectId(), context.root(), path, MAX_BYTES + 1);
        if (isBinary(bytes)) throw new IllegalArgumentException("Binary files cannot be read as text");

        boolean truncated = bytes.length > MAX_BYTES;
        String content = new String(truncated ? Arrays.copyOf(bytes, MAX_BYTES) : bytes, StandardCharsets.UTF_8);
        context.contextTrace().recordRead(path, size, version, content.length());
        return Map.of("path", path, "version", version, "content", content,
                "truncated", truncated, "sizeBytes", size);
    }

    /**
     * Secret/credential files whose contents must never reach the model. Blocks reads only: the
     * agent can still reference config by name or scaffold a {@code .env.example}.
     */
    public static boolean isSecretFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.equals(".env") || (lower.startsWith(".env.") && !lower.endsWith(".example"))) return true;
        if (lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12") || lower.endsWith(".pfx")) return true;
        if (lower.equals(".npmrc") || lower.equals(".pgpass") || lower.equals(".htpasswd") || lower.equals("credentials")) return true;
        return lower.equals("id_rsa") || lower.equals("id_dsa") || lower.equals("id_ecdsa") || lower.equals("id_ed25519");
    }

    /** Heuristic: a NUL byte in the first chunk → binary. */
    private static boolean isBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static String str(Object v) {
        if (v == null) throw new IllegalArgumentException("missing 'path'");
        return v.toString();
    }
}
