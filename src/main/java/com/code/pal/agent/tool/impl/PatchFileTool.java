package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import com.code.pal.agent.tool.AgentToolException;
import java.nio.file.Path;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PatchFileTool implements AgentTool {
    private final ChangeSetService changeSets;

    @Override
    public String name() {
        return "patchFile";
    }

    @Override
    public String description() {
        return "Replace exact, unique text blocks in an existing file: oldText/newText for one block, or edits "
                + "[{oldText,newText}] to change several blocks of the same file in ONE call (applied in order). Requires the "
                + "version returned by readFile and fails on stale or ambiguous content.";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string"), "expectedVersion", Map.of("type", "string"), "oldText",
                        Map.of("type", "string"), "newText", Map.of("type", "string"), "edits",
                        Map.of("type", "array", "minItems", 1, "maxItems", MAX_EDITS, "items", Map.of("type", "object",
                                "properties", Map.of("oldText", Map.of("type", "string"), "newText", Map.of("type", "string")),
                                "required", List.of("oldText", "newText")))),
                "required", List.of("path", "expectedVersion"));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        String path = Path.of(context.resolvePath(CreateFileTool.text(args, "path"))).normalize().toString()
                .replace('\\', '/');
        if (context.contextTrace().patchLimitReached())
            throw new AgentToolException("PATCH_STAGNATION", "Repeated patch misses exhausted the configured limit");
        try {
            if (context.contextTrace().patchNeedsRead(path))
                throw new AgentToolException("PATCH_REREAD_REQUIRED", "Read this file again before retrying the patch",
                        Map.of("path", path, "actualVersion",
                                context.files().version(context.projectId(), context.root(), path), "action",
                                "Call readFile, then use its version and a unique target"));
            String expected = args.get("expectedVersion") instanceof String value ? value : null;
            Object result;
            if (args.get("edits") instanceof List<?> edits) {
                result = patchAll(context, path, expected, edits);
            } else {
                Object replacement = args.get("newText");
                if (!(replacement instanceof String next))
                    throw new IllegalArgumentException("newText must be a string; use empty text to delete a block");
                result = changeSets.patch(context, path, expected, CreateFileTool.text(args, "oldText"), next);
            }
            context.contextTrace().patchSucceeded(path);
            return result;
        } catch (AgentToolException error) {
            recordMiss(context, path, error);
            throw error;
        }
    }

    private static final int MAX_EDITS = 20;

    /** Several blocks of one file in one version-checked write; every target must match exactly once, in order. */
    private Object patchAll(AgentContext context, String path, String expected, List<?> edits) {
        if (edits.isEmpty() || edits.size() > MAX_EDITS)
            throw new IllegalArgumentException("edits must have 1 to " + MAX_EDITS + " entries");
        String actual = context.files().version(context.projectId(), context.root(), path);
        if (expected == null || !expected.equals(actual))
            throw new AgentToolException("STALE_FILE_VERSION", "File version no longer matches",
                    Map.of("path", path, "actualVersion", actual));
        String original = context.files().readText(context.projectId(), context.root(), path);
        String updated = original;
        for (int index = 0; index < edits.size(); index++) {
            if (!(edits.get(index) instanceof Map<?, ?> edit) || !(edit.get("oldText") instanceof String old)
                    || !(edit.get("newText") instanceof String next) || old.isEmpty())
                throw new IllegalArgumentException("edits[" + index + "] needs a non-empty oldText and a newText string");
            int first = updated.indexOf(old);
            if (first < 0)
                throw new AgentToolException("PATCH_TARGET_NOT_FOUND", "edits[" + index + "].oldText was not found",
                        Map.of("path", path, "edit", index));
            if (updated.indexOf(old, first + 1) >= 0)
                throw new AgentToolException("PATCH_TARGET_AMBIGUOUS", "edits[" + index + "].oldText matches more than once",
                        Map.of("path", path, "edit", index));
            updated = updated.substring(0, first) + next + updated.substring(first + old.length());
        }
        return changeSets.patch(context, path, expected, original, updated);
    }

    private static void recordMiss(AgentContext context, String path, AgentToolException error) {
        if (Set.of("PATCH_TARGET_NOT_FOUND", "PATCH_TARGET_AMBIGUOUS", "PATCH_TARGET_EMPTY", "STALE_FILE_VERSION",
                "EXPECTED_VERSION_REQUIRED", "PATCH_REREAD_REQUIRED", "FILE_NOT_FOUND").contains(error.code()))
            context.contextTrace().patchFailed(path);
    }
}
