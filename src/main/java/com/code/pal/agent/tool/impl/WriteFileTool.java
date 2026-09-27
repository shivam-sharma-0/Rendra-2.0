package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Create or overwrite a text file in the project workspace (BUILD mode only). */
@Deprecated(forRemoval = true)
public class WriteFileTool implements AgentTool {

    private static final int MAX_BYTES = 1_000_000;

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Create or overwrite a file with the given content (path relative to project root).";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path relative to project root"),
                        "content", Map.of("type", "string", "description", "Full file content to write")),
                "required", List.of("path", "content"));
    }

    @Override
    public String execute(Map<String, Object> args, AgentContext context) throws Exception {
        String path = require(args.get("path"), "path");
        String content = args.get("content") == null ? "" : args.get("content").toString();

        Path file = context.resolveSafe(path);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return "Error: file content exceeds the " + MAX_BYTES + " byte write limit.";
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(file)) {
            return "Error: refusing to overwrite a symbolic link: " + path;
        }
        Files.createDirectories(file.getParent());
        // Re-check after creating parent directories, then write and replace atomically so a
        // cancelled/failed run cannot leave a half-written source file behind.
        file = context.resolveSafe(path);
        Path temporary = Files.createTempFile(file.getParent(), ".rendra-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return "Wrote " + bytes.length + " bytes to " + path;
    }

    private static String require(Object v, String name) {
        if (v == null) throw new IllegalArgumentException("missing '" + name + "'");
        return v.toString();
    }
}
