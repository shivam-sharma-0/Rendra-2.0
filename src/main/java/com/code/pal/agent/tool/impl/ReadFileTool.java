package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Read a text file from the project workspace. Guards against binaries and caps size. */
@Component
public class ReadFileTool implements AgentTool {

    private static final int MAX_BYTES = 100_000; // ~100 KB; larger files are truncated

    @Override
    public String name() {
        return "read_file";
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
    public String execute(Map<String, Object> args, AgentContext context) throws Exception {
        String path = str(args.get("path"));
        Path file = context.resolveSafe(path);
        if (!Files.exists(file) || !Files.isRegularFile(file)) return "Error: file not found: " + path;

        byte[] bytes = Files.readAllBytes(file);
        if (isBinary(bytes)) return "Error: '" + path + "' looks binary; cannot read as text.";

        boolean truncated = bytes.length > MAX_BYTES;
        String content = new String(truncated ? java.util.Arrays.copyOf(bytes, MAX_BYTES) : bytes, StandardCharsets.UTF_8);
        return truncated ? content + "\n\n…[truncated — file larger than " + MAX_BYTES + " bytes]" : content;
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
