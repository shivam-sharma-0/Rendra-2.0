package com.code.pal.agent.context;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.message.domain.Message;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectContextServiceTest {
    LocalProject project;
    Path workspace;

    @BeforeEach
    void createWorkspace() {
        project = LocalProject.create();
        workspace = project.dir();
    }

    @Test
    void theProjectBriefIsTheFirstInstructionFile() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "Agent notes.");
        Files.writeString(workspace.resolve("RENDRA.md"), "# Game\n\n## Decisions\n- Purple accent");
        Files.writeString(workspace.resolve("index.html"), "<h1>Game</h1>");
        ProjectContextService service = new ProjectContextService(new AgentProperties(), new ObjectMapper());

        String message = service.build(project.files, project.projectId, ".", "Make it bigger", List.of(), List.of()).projectContextMessage();

        int brief = message.indexOf("--- RENDRA.md ---");
        assertTrue(brief >= 0 && brief < message.indexOf("--- AGENTS.md ---"), message);
        assertTrue(message.contains("Purple accent"));
        assertFalse(message.contains("- RENDRA.md ["), "a file shown in full gets no summary line as well");
    }

    @Test
    void requestsLeftOutOfTheBudgetAreListedOneLineEach() {
        AgentProperties properties = new AgentProperties();
        properties.setConversationBudgetChars(1_200);
        ProjectContextService service = new ProjectContextService(properties, new ObjectMapper());
        List<Message> history = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            history.add(message("old-" + index, "Request number " + index + "\n" + "detail ".repeat(60), index));
        }
        history.add(message("current", "Make the header blue", 100));

        ProjectContext context = service.build(project.files, project.projectId, ".", "Make the header blue", history, List.of());

        String digest = context.conversation().getFirst().content();
        assertTrue(digest.startsWith("<earlier_conversation>") && digest.endsWith("</earlier_conversation>"), digest);
        assertTrue(digest.contains("- Request number 12 detail") && digest.contains("older requests not listed)"), digest);
        assertFalse(digest.contains("Request number 19 "), "requests kept in full are not listed again");
        assertFalse(digest.contains("\nRequest number"), "each request stays on one line");
        assertTrue(digest.length() <= properties.getConversationDigestChars());
        assertTrue(context.conversation().getLast().content().contains("Make the header blue"));
    }

    @Test
    void theDigestKeepsTheNewestRequestsAndSkipsReplies() {
        List<Message> candidates = new ArrayList<>();
        for (int index = 0; index < 30; index++) candidates.add(message("u" + index, "Add feature " + index, index));
        Message reply = Message.assistantPlaceholder(UUID.randomUUID(), "a", "u0", ProjectType.CHAT);
        reply.setContent("I added the feature.");
        candidates.add(reply);

        String digest = ProjectContextService.digest(candidates, java.util.Set.of(29), 400);

        assertTrue(digest.length() <= 400, digest);
        assertTrue(digest.contains("- Add feature 28\n") && !digest.contains("Add feature 29"), digest);
        assertTrue(digest.contains("older requests not listed)"), digest);
        assertFalse(digest.contains("- Add feature 0\n"));
        assertFalse(digest.contains("I added the feature"));
        assertEquals("", ProjectContextService.digest(candidates, java.util.Set.of(29), 0), "0 switches it off");
        assertEquals("", ProjectContextService.digest(candidates.subList(0, 1), java.util.Set.of(0), 400), "nothing left out");
    }

    @Test
    void buildsCompactReactMapAndPrioritizesTheCurrentFeature() throws Exception {
        Files.createDirectories(workspace.resolve("src/features/auth"));
        Files.createDirectories(workspace.resolve("src/features/catalog"));
        Files.createDirectories(workspace.resolve("node_modules/vendor"));
        Files.createDirectories(workspace.resolve("dist/assets"));
        Files.writeString(workspace.resolve("package.json"), """
                {"dependencies":{"react":"19.0.0","react-router-dom":"7.0.0","@tanstack/react-query":"5.0.0"},
                 "devDependencies":{"vite":"7.0.0","typescript":"5.0.0"}}
                """);
        Files.writeString(workspace.resolve("AGENTS.md"), "Use functional components and the existing API client.");
        Files.writeString(workspace.resolve("src/App.tsx"), """
                import { Route } from "react-router-dom";
                export function App() { return <Route path="/login" element={<Login />} />; }
                """);
        Files.writeString(workspace.resolve("src/features/auth/Login.tsx"), """
                import { requestPasswordReset } from "./authApi";
                // FULL_LOGIN_IMPLEMENTATION_SHOULD_NOT_BE_IN_THE_MAP
                export function Login() { return null; }
                """);
        Files.writeString(workspace.resolve("src/features/auth/authApi.ts"),
                "export const requestPasswordReset = () => fetch('/forgot-password');");
        Files.writeString(workspace.resolve("src/features/catalog/Catalog.tsx"),
                "export function Catalog() { return null; }");
        Files.createDirectories(workspace.resolve("src/components/generated-sample"));
        for (int index = 0; index < 220; index++) {
            Files.writeString(workspace.resolve("src/components/generated-sample/Widget" + index + ".tsx"),
                    "export function Widget" + index + "() { return <div />; }");
        }
        Files.writeString(workspace.resolve("node_modules/vendor/index.js"), "ignored dependency source");
        project.write(".codepal/history/run/original/src/Old.tsx", "export function Old() {}");
        Files.writeString(workspace.resolve("dist/assets/app.js"), "ignored generated output");
        Files.write(workspace.resolve("src/logo.png"), new byte[]{0, 1, 2, 3});

        AgentProperties properties = new AgentProperties();
        properties.setContextBudgetChars(12_000);
        properties.setProjectMapBudgetChars(7_000);
        properties.setConversationBudgetChars(1_200);
        ProjectContextService service = new ProjectContextService(properties, new ObjectMapper());

        List<Message> history = history(18);
        history.add(message("current", "Add forgot password to src/features/auth/Login.tsx", 100));
        ProjectContext context = service.build(project.files, project.projectId, ".",
                "Add forgot password to src/features/auth/Login.tsx", history, List.of());

        assertTrue(context.projectMap().frameworks().containsAll(List.of("React", "Vite", "React Router", "TypeScript")));
        assertTrue(context.projectMap().routes().stream().anyMatch(route -> route.startsWith("/login")));
        assertTrue(context.projectMap().featureModules().contains("src/features/auth"));
        assertFalse(context.projectMap().importantFiles().stream().anyMatch(file -> file.path().contains("node_modules")));
        assertFalse(context.projectMap().importantFiles().stream().anyMatch(file -> file.path().contains(".codepal")),
                "the backend's own state (history, staging) is not part of the project map");
        assertFalse(context.projectContextMessage().contains("FULL_LOGIN_IMPLEMENTATION_SHOULD_NOT_BE_IN_THE_MAP"));
        assertTrue(context.projectContextMessage().contains("trust=\"untrusted-project-data\""));
        assertTrue(context.projectContextMessage().contains("AGENTS.md"));
        assertTrue(context.conversation().stream().anyMatch(item -> item.content().contains("forgot password")));
        assertTrue(context.diagnostics().selectedFiles().stream().anyMatch(item ->
                item.path().equals("src/features/auth/Login.tsx")
                        && item.reason() == ContextSelectionReason.USER_MENTIONED));
        assertTrue(context.diagnostics().omittedConversationMessages() > 0);
        assertTrue(context.diagnostics().approximateChars() <= properties.getContextBudgetChars());
        assertTrue(context.projectMap().scannedFileCount() > 200);
        assertTrue(context.diagnostics().selectedFiles().size() < 80,
                "a feature request should not select hundreds of unrelated files");
    }

    @Test
    void theAgentSeesVariableNamesButNeverTheirValues() {
        project.write("package.json", "{}");
        ProjectContextService service = new ProjectContextService(new AgentProperties(), new ObjectMapper(),
                projectId -> java.util.Map.of("VITE_SUPABASE_URL", "https://secret-project.supabase.co"));

        ProjectContext context = service.build(project.files, project.projectId, ".", "Add login", List.of(), List.of());

        assertTrue(context.projectContextMessage().contains("VITE_SUPABASE_URL"));
        assertFalse(context.projectContextMessage().contains("secret-project"));
    }

    @Test
    void summarizesImportsAndExportsWithoutReturningSourceBody() throws Exception {
        Path file = workspace.resolve("src/features/auth/Login.tsx");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                import { api } from "../../api/client";
                import React from "react";
                export interface LoginProps { email: string }
                export function Login() { return api.post('/login'); }
                """);
        ProjectContextService service = new ProjectContextService(new AgentProperties(), new ObjectMapper());

        ProjectFileSummary summary = service.summarizeFile(project.files, project.projectId, ".", "src/features/auth/Login.tsx");

        assertEquals("src/features/auth/Login.tsx", summary.path());
        assertTrue(summary.imports().contains("../../api/client"));
        assertTrue(summary.imports().contains("react"));
        assertTrue(summary.exports().contains("LoginProps"));
        assertTrue(summary.exports().contains("Login"));
    }

    private static List<Message> history(int count) {
        List<Message> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            messages.add(message("old-" + index, "Unrelated historical discussion " + index + " ".repeat(40), index));
        }
        return messages;
    }

    private static Message message(String id, String content, int seconds) {
        Message message = Message.user(UUID.randomUUID(), id, content, ProjectType.CHAT, 7L);
        message.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds));
        return message;
    }
}
