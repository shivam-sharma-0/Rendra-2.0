package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.change.FileVersions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.InputStream;
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
    public Object execute(Map<String, Object> args, AgentContext context) throws Exception {
        String path = str(args.get("path"));
        if (context.sandboxed()) return readSandbox(path, context);
        Path file = context.resolveSafe(path);
        if (isSecretFile(file.getFileName().toString())) {
            throw new IllegalArgumentException("Credential-like files are not available to agent tools");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
        long size = Files.size(file);
        String version = FileVersions.of(file);
        String normalizedPath = context.workspace().toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (context.contextTrace().wasReadUnchanged(normalizedPath, size, version)) {
            return Map.of("path", normalizedPath, "version", version, "unchanged", true,
                    "message", "Unchanged file already present in this run's conversation");
        }

        // Read at most one byte over the cap. Files.readAllBytes() would allocate the full file
        // before truncating, letting a large repository file exhaust the agent worker's heap.
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        if (isBinary(bytes)) throw new IllegalArgumentException("Binary files cannot be read as text");

        boolean truncated = bytes.length > MAX_BYTES;
        String content = new String(truncated ? java.util.Arrays.copyOf(bytes, MAX_BYTES) : bytes, StandardCharsets.UTF_8);
        context.contextTrace().recordRead(normalizedPath, size, version, content.length());
        return Map.of("path", normalizedPath, "version", version, "content", content,
                "truncated", truncated, "sizeBytes", size);
    }

    private Object readSandbox(String requested, AgentContext context) {
        String path = context.resolveSandboxPath(requested);
        if (isSecretFile(Path.of(path).getFileName().toString())) {
            throw new IllegalArgumentException("Credential-like files are not available to agent tools");
        }
        var sandbox = context.sandbox();
        if (!sandbox.regularFile(context.projectId(), context.sandboxRoot(), path)) {
            throw new IllegalArgumentException("File not found: " + requested);
        }
        long size = sandbox.size(context.projectId(), context.sandboxRoot(), path);
        String version = sandbox.version(context.projectId(), context.sandboxRoot(), path);
        if (context.contextTrace().wasReadUnchanged(path, size, version)) {
            return Map.of("path", path, "version", version, "unchanged", true,
                    "message", "Unchanged file already present in this run's conversation");
        }
        String raw = sandbox.readText(context.projectId(), context.sandboxRoot(), path);
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (isBinary(bytes)) throw new IllegalArgumentException("Binary files cannot be read as text");
        boolean truncated = bytes.length > MAX_BYTES;
        String content = truncated ? new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8) : raw;
        context.contextTrace().recordRead(path, size, version, content.length());
        return Map.of("path", path, "version", version, "content", content,
                "truncated", truncated, "sizeBytes", size);
    }

    /**
     * Secret/credential files whose contents must never reach the model. Blocks reads only â€” the
     * agent can still reference config by name or scaffold a {@code .env.example}.
     */
    private static boolean isSecretFile(String fileName) {
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
