package com.code.pal.agent.tool;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.context.ProjectFileSummary;
import com.code.pal.agent.tool.impl.GetFileSummaryTool;
import com.code.pal.agent.tool.impl.ReadFileTool;
import com.code.pal.agent.tool.impl.GetProjectMetadataTool;
import com.code.pal.agent.tool.impl.SearchCodeTool;
import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.ProjectNotFoundException;
import com.code.pal.project.service.ProjectService;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentReadOnlyToolsTest {
    private LocalProject project;
    private UUID projectId;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        project = LocalProject.create();
        workspace = project.dir();
        projectId = project.projectId;
    }

    @Test
    void searchesSourceFilesWithGroundingAndSkipsCredentialFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src/security"));
        Files.writeString(workspace.resolve("src/security/AuthService.java"),
                "class AuthService {\n  void authenticate() {}\n}\n");
        Files.writeString(workspace.resolve(".env"), "AUTH_SECRET=authenticate");

        SearchCodeTool tool = new SearchCodeTool();
        Map<String, Object> result = tool.execute(Map.of("query", "authenticate", "path", "."), context());

        assertEquals(1, result.get("matchCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertEquals("src/security/AuthService.java", matches.getFirst().get("path"));
        assertEquals(2, matches.getFirst().get("line"));
        assertFalse(matches.getFirst().get("text").toString().contains("AUTH_SECRET"));
    }

    @Test
    void validatesSearchInputBeforeExecution() {
        SearchCodeTool tool = new SearchCodeTool();

        assertTrue(tool.validate(Map.of()).stream().anyMatch(error -> error.code().equals("REQUIRED")));
        assertTrue(tool.validate(Map.of("query", "auth", "maxResults", 101)).stream()
                .anyMatch(error -> error.code().equals("ABOVE_MAXIMUM")));
        assertTrue(tool.validate(Map.of("query", "auth", "unexpected", true)).stream()
                .anyMatch(error -> error.code().equals("UNKNOWN_FIELD")));
    }

    @Test
    void returnsOnlySafeAlreadyLoadedProjectMetadata() {
        UUID workspaceId = UUID.randomUUID();
        AgentProjectMetadata metadata = new AgentProjectMetadata(projectId, workspaceId, "Authentication API",
                ProjectType.CHAT, false, DeploymentStatus.NONE, Instant.parse("2026-01-01T00:00:00Z"), null);
        AgentContext context = new AgentContext(projectId, 7L, ProjectType.CHAT, ".", metadata,
                new com.code.pal.agent.context.ProjectContextTrace(), UUID.randomUUID(),
                new com.code.pal.agent.plan.AgentPlanState(), project.files);

        Map<String, Object> result = new GetProjectMetadataTool().execute(Map.of(), context);

        assertEquals(projectId.toString(), result.get("projectId"));
        assertEquals(workspaceId.toString(), result.get("workspaceId"));
        assertEquals("Authentication API", result.get("name"));
        assertFalse(result.containsKey("ownerId"));
    }

    @Test
    void rejectsParentSegmentsEvenWhenNormalizationWouldRemainInsideWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> context().resolvePath("src/../pom.xml"));
    }

    @Test
    void doesNotResendAnUnchangedFileWithinOneRun() throws Exception {
        Files.writeString(workspace.resolve("Login.tsx"), "export function Login() { return null; }");
        AgentContext context = context();
        ReadFileTool tool = new ReadFileTool();

        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) tool.execute(Map.of("path", "Login.tsx"), context);
        @SuppressWarnings("unchecked") Map<String, Object> second = (Map<String, Object>) tool.execute(Map.of("path", "Login.tsx"), context);

        assertTrue(first.get("content").toString().contains("function Login"));
        assertEquals(true, second.get("unchanged"));
        assertTrue(context.contextTrace().diagnostics(first.get("content").toString().length()).selectedFiles().stream()
                .anyMatch(item -> item.path().equals("Login.tsx")
                        && item.reason() == com.code.pal.agent.context.ContextSelectionReason.FILE_READ));
    }

    @Test
    void fileSummaryDiscoversDirectImportsWithoutReadingTheirContents() throws Exception {
        Files.createDirectories(workspace.resolve("src/auth"));
        Files.writeString(workspace.resolve("src/auth/Login.tsx"),
                "import { login } from './api';\nexport function Login() { return login(); }");
        Files.writeString(workspace.resolve("src/auth/api.ts"), "export const login = () => 'secret body';");
        AgentContext context = context();
        GetFileSummaryTool tool = new GetFileSummaryTool(
                new ProjectContextService(new AgentProperties(), new ObjectMapper()));

        ProjectFileSummary summary = tool.execute(Map.of("path", "src/auth/Login.tsx"), context);

        assertTrue(summary.imports().contains("./api"));
        assertTrue(context.contextTrace().diagnostics(0).selectedFiles().stream().anyMatch(item ->
                item.path().equals("src/auth/api.ts") && item.reason() == ContextSelectionReason.DIRECT_IMPORT));
    }

    @Test
    void trustedExecutorReauthorizesAndReturnsStructuredErrors() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "safe content");
        ProjectService projects = mock(ProjectService.class);
        ReadFileTool readFile = new ReadFileTool();
        AgentToolExecutor executor = new AgentToolExecutor(
                new AgentProperties(), new ToolRegistry(List.of(readFile)), projects);
        executor.start();
        try {
            ToolExecutionResult allowed = executor.execute("readFile", List.of("readFile"),
                    Map.of("path", "README.md"), context());
            assertTrue(allowed.success());
            verify(projects).requireAccessible(7L, projectId);

            doThrow(new ProjectNotFoundException()).when(projects).requireAccessible(7L, projectId);
            ToolExecutionResult denied = executor.execute("readFile", List.of("readFile"),
                    Map.of("path", "README.md"), context());
            assertFalse(denied.success());
            assertEquals("ACCESS_DENIED", denied.error().code());

            ToolExecutionResult unavailable = executor.execute("write_file", List.of("readFile"),
                    Map.of(), context());
            assertEquals("TOOL_NOT_AVAILABLE", unavailable.error().code());
        } finally {
            executor.stop();
        }
    }

    private AgentContext context() {
        return new AgentContext(projectId, 7L, ProjectType.CHAT, ".", project.files);
    }
}
