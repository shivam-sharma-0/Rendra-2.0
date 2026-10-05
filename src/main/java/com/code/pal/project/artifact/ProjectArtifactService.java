package com.code.pal.project.artifact;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.project.domain.RequirementQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

/**
 * Writes durable workflow manifests ({@code requirements.json}, {@code builds/<runId>.json}) under the
 * project's internal folder, outside the agent staging tree. Change-set staging and build history live
 * in the same folder but are not artifacts.
 */
@Service
@RequiredArgsConstructor
public class ProjectArtifactService {
    private static final int MAX_READ_BYTES = 512_000;
    private static final String ROOT = ProjectPaths.INTERNAL;
    private static final Set<String> NOT_ARTIFACTS = Set.of("changesets", "history");

    private final ProjectFiles files;
    private final ObjectMapper objectMapper;

    public void writeRequirements(UUID projectId, UUID sessionId, List<RequirementQuestion> questions) {
        List<Map<String, Object>> answers = questions.stream().map(question -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", question.getQuestionKey());
            row.put("prompt", question.getPrompt());
            row.put("selectedOption", question.getSelectedOption());
            row.put("otherText", question.getOtherText());
            return row;
        }).toList();
        writeJson(projectId, "requirements.json", Map.of(
                "kind", "requirements", "sessionId", sessionId.toString(), "updatedAt", Instant.now(), "answers", answers));
    }

    public void writeBuild(AgentRun run, String outcome, List<String> changedPaths) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kind", "build");
        manifest.put("runId", run.getId().toString());
        manifest.put("projectId", run.getProjectId().toString());
        manifest.put("status", outcome);
        manifest.put("snapshotRef", run.getSnapshotRef());
        manifest.put("changedPaths", changedPaths == null ? List.of() : List.copyOf(changedPaths));
        manifest.put("promptTokens", run.getPromptTokens());
        manifest.put("completionTokens", run.getCompletionTokens());
        manifest.put("updatedAt", Instant.now());
        writeJson(run.getProjectId(), "builds/" + run.getId() + ".json", manifest);
    }

    public List<Artifact> list(UUID projectId) {
        return files.list(projectId, ROOT, 8, NOT_ARTIFACTS).stream().filter(entry -> !entry.directory())
                .map(entry -> new Artifact(entry.path(), entry.size(), Instant.now()))
                .sorted(Comparator.comparing(Artifact::path)).toList();
    }

    public String read(UUID projectId, String relativePath) {
        String path;
        try {
            path = ProjectPaths.normalize(relativePath, false);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid artifact path");
        }
        if (NOT_ARTIFACTS.contains(path.split("/", 2)[0])) throw new IllegalArgumentException("Artifact not found");
        if (!files.regularFile(projectId, ROOT, path)) throw new IllegalArgumentException("Artifact not found");
        if (files.size(projectId, ROOT, path) > MAX_READ_BYTES) throw new IllegalArgumentException("Artifact is too large");
        return files.readText(projectId, ROOT, path);
    }

    private void writeJson(UUID projectId, String relativePath, Object value) {
        files.writeText(projectId, ROOT, relativePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    public record Artifact(String path, long size, Instant updatedAt) {}
}
