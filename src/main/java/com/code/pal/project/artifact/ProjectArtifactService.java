package com.code.pal.project.artifact;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.domain.RequirementQuestion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Writes durable workflow manifests beside the generated project, outside the agent staging tree. */
@Service
@RequiredArgsConstructor
public class ProjectArtifactService {
    private static final int MAX_READ_BYTES = 512_000;
    private final WorkspaceStorage workspaces;
    private final ObjectMapper objectMapper;
    private final DockerProjectEnvironment sandbox;
    private final AgentProperties properties;

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
        if (properties.getSandbox().isRequired()) {
            return sandbox.list(projectId, ".codepal", 8).stream().filter(entry -> !entry.directory())
                    .map(entry -> new Artifact(entry.path(), entry.size(), Instant.now())).sorted(Comparator.comparing(Artifact::path)).toList();
        }
        Path root = workspaces.projectDir(projectId).resolve(".codepal").normalize();
        if (!root.startsWith(workspaces.projectDir(projectId)) || !Files.isDirectory(root)) return List.of();
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                try { return new Artifact(relative, Files.size(path), Files.getLastModifiedTime(path).toInstant()); }
                catch (IOException e) { return new Artifact(relative, 0, Instant.EPOCH); }
            }).sorted(Comparator.comparing(Artifact::path)).toList();
        } catch (IOException e) { return List.of(); }
    }

    public String read(UUID projectId, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..") || relativePath.startsWith("/")) {
            throw new IllegalArgumentException("Invalid artifact path");
        }
        if (properties.getSandbox().isRequired()) {
            if (!sandbox.regularFile(projectId, ".codepal", relativePath)) throw new IllegalArgumentException("Artifact not found");
            if (sandbox.size(projectId, ".codepal", relativePath) > MAX_READ_BYTES) throw new IllegalArgumentException("Artifact is too large");
            return sandbox.readText(projectId, ".codepal", relativePath);
        }
        Path root = workspaces.projectDir(projectId).resolve(".codepal").normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) throw new IllegalArgumentException("Artifact not found");
        try {
            if (Files.size(target) > MAX_READ_BYTES) throw new IllegalArgumentException("Artifact is too large");
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Could not read artifact", e); }
    }

    private void writeJson(UUID projectId, String relativePath, Object value) {
        if (properties.getSandbox().isRequired()) {
            sandbox.writeText(projectId, ".codepal", relativePath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            return;
        }
        Path root = workspaces.ensure(projectId).resolve(".codepal").normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid artifact path");
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
            Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Could not persist project artifact", e); }
    }

    public record Artifact(String path, long size, Instant updatedAt) {}
}
