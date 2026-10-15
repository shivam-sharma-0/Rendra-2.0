package com.code.pal.agent.repair;

import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.contract.ModelJson;
import com.code.pal.agent.contract.UnitGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Validate everything and compute all final bodies before any filesystem mutation is possible. */
public final class RepairPatches {
    public record Scope(String batchId, String diagnosticsRevision, Set<String> allowedPaths, Map<String, UnitGenerator.FileView> snapshot) {
        public Scope {
            BuildContract.identifier(batchId);
            if (diagnosticsRevision == null || !diagnosticsRevision.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Missing diagnostics identity");
            }
            allowedPaths = Set.copyOf(allowedPaths);
            snapshot = Map.copyOf(snapshot);
        }
    }

    private record Range(int start, int end, String replacement) {
    }

    private final ObjectMapper mapper;

    public RepairPatches(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RepairPatch decode(String response, String finishReason) {
        try {
            if (response == null || response.length() > 48_000 || response.getBytes(StandardCharsets.UTF_8).length > 64_000
                    || !"stop".equals(finishReason)) {
                throw new IllegalArgumentException("Repair response truncated or exceeds capacity");
            }
            return mapper.readerFor(RepairPatch.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(ModelJson.object(response));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Repair response is not one complete strict patch object", malformed);
        }
    }

    public List<UnitGenerator.Delta> prepare(Scope scope, RepairPatch patch) {
        if (patch.schemaVersion() != 1 || !scope.batchId().equals(patch.batchId())
                || !scope.diagnosticsRevision().equals(patch.diagnosticsRevision()) || patch.patches().isEmpty() || patch.patches().size() > 12) {
            throw new IllegalArgumentException("Repair identity or file count changed");
        }
        String json = mapper.writeValueAsString(patch);
        if (json.length() > 48_000 || json.getBytes(StandardCharsets.UTF_8).length > 64_000) {
            throw new IllegalArgumentException("Repair exceeds bounded capacity");
        }
        var paths = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var changes = new ArrayList<UnitGenerator.Delta>();
        for (var file : patch.patches()) {
            BuildContract.canonical(file.path());
            if (!scope.allowedPaths().contains(file.path()) || !paths.add(file.path()) || file.edits().isEmpty() || file.edits().size() > 16) {
                throw new IllegalArgumentException("Repair exceeds diagnosed file scope");
            }
            var before = scope.snapshot().get(file.path());
            if (before == null || before.content() == null || !before.version().equals(file.expectedVersion())
                    || !before.version().equals("sha256:" + ContractStore.hash(before.content()))) {
                throw new IllegalArgumentException("Repair target is missing or stale");
            }
            var ranges = new ArrayList<Range>();
            for (var edit : file.edits()) {
                if (edit.search() == null || edit.search().isEmpty() || edit.search().length() > 8_000
                        || edit.replace() == null || edit.replace().length() > 8_000) {
                    throw new IllegalArgumentException("Invalid repair edit size");
                }
                int start = before.content().indexOf(edit.search());
                if (start < 0 || before.content().indexOf(edit.search(), start + 1) >= 0) {
                    throw new IllegalArgumentException("Repair search must match exactly once");
                }
                ranges.add(new Range(start, start + edit.search().length(), edit.replace()));
            }
            ranges.sort(Comparator.comparingInt(Range::start));
            int end = -1;
            for (var range : ranges) {
                if (range.start() < end) {
                    throw new IllegalArgumentException("Repair edits overlap");
                }
                end = range.end();
            }
            var after = new StringBuilder(before.content());
            for (var range : ranges.reversed()) {
                after.replace(range.start(), range.end(), range.replacement());
            }
            validateText(after.toString());
            changes.add(new UnitGenerator.Delta(file.path(), before.version(), after.toString()));
        }
        return List.copyOf(changes);
    }

    private static void validateText(String content) {
        if (content.length() > 48_000 || content.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Repaired source exceeds capacity");
        }
        try {
            if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content)).remaining() > 64_000) {
                throw new IllegalArgumentException("Repaired source exceeds UTF-8 capacity");
            }
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Repaired source is not valid UTF-8", invalid);
        }
    }
}
