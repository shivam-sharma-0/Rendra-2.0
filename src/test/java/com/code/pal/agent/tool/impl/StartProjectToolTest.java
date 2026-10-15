package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.FileChangeRepository;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StartProjectToolTest {
    private final LocalProject project = LocalProject.create();
    private final ChangeSetService changeSets = new ChangeSetService(mock(FileChangeRepository.class), mock(AgentRunRepository.class),
            new ObjectMapper(), project.properties, project.files, project.sandbox);
    private final StartProjectTool tool = new StartProjectTool(changeSets);

    private AgentContext context() {
        UUID runId = UUID.randomUUID();
        String root = changeSets.start(runId, project.projectId);
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Set up", "Build the app"), "Initial plan");
        return new AgentContext(project.projectId, 7L, ProjectType.BUILD, root, null, new ProjectContextTrace(), runId, plan, project.files);
    }

    @Test
    void writesTheStarterIntoTheStagedChangeOnly() {
        project.write("RENDRA.md", "# Brief");
        AgentContext context = context();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of("stack", "vite-react", "title", "Tip calculator"), context);

        List<?> created = (List<?>) result.get("createdFiles");
        assertTrue(created.containsAll(List.of("package.json", "vite.config.ts", "src/main.tsx")), created.toString());
        assertTrue(project.files.regularFile(project.projectId, context.root(), "package.json"));
        assertTrue(project.files.readText(project.projectId, context.root(), "index.html").contains("<title>Tip calculator</title>"));
        assertFalse(project.exists("package.json"), "the live project changes only when the change set is committed");
        assertTrue(result.get("next").toString().contains("src/App.tsx"));
    }

    @Test
    void anExistingSetupIsKept() {
        project.write("package.json", "{}");
        AgentContext context = context();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("stack", "mern"), context));
        assertTrue(error.getMessage().contains("already has package.json"), error.getMessage());
    }

    @Test
    void nothingIsWrittenWhenOneOfItsFilesExists() {
        project.write("index.html", "<h1>mine</h1>");
        AgentContext context = context();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("stack", "vite-react"), context));
        assertTrue(error.getMessage().contains("index.html"), error.getMessage());
        assertFalse(project.files.exists(project.projectId, context.root(), "package.json"));
    }

    @Test
    void anUnknownStackIsRefused() {
        AgentContext context = context();
        assertThrows(IllegalArgumentException.class, () -> tool.execute(Map.of("stack", "django"), context));
        assertEquals("startProject", tool.name());
    }
}
