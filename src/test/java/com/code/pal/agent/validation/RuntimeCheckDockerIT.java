package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.FileChangeRepository;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.agent.workspace.HostProjectFiles;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The runtime check with a real headless Chromium: a staged site that throws when it loads fails the
 * final gate with the error the browser saw; the repaired site passes and commits. Opt-in (builds the
 * browser image on first use): {@code mvnw test -Dtest=RuntimeCheckDockerIT -Dcodepal.docker.it=true}.
 */
@EnabledIfSystemProperty(named = "codepal.docker.it", matches = "true")
class RuntimeCheckDockerIT {
    private static final String DISTRO = System.getProperty("codepal.wsl.distro", "Ubuntu-24.04");
    private static final String LINUX_BASE = System.getProperty("codepal.wsl.base", "/home/shivam/codepal-it/workspaces");

    private final AgentProperties properties = new AgentProperties();
    private final UUID projectId = UUID.randomUUID();
    private DockerProjectEnvironment sandbox;

    @AfterEach
    void cleanUp() throws Exception {
        run("docker", "rm", "-f", properties.getSandbox().getContainerPrefix() + projectId);
        run("rm", "-rf", LINUX_BASE + "/" + projectId);
    }

    @Test
    void aSiteThatThrowsOnLoadFailsTheGateUntilItIsRepaired() {
        properties.getSandbox().setRequired(true);
        properties.getSandbox().setDockerCommand("wsl.exe");
        properties.getSandbox().setDockerCommandArgs("-e docker");
        properties.setWorkspaceBasePath("//wsl.localhost/" + DISTRO + LINUX_BASE);
        WorkspaceStorage storage = new WorkspaceStorage(properties);
        sandbox = new DockerProjectEnvironment(properties, storage);
        HostProjectFiles files = new HostProjectFiles(storage);
        ObjectMapper mapper = new ObjectMapper();
        StackPacks stacks = new StackPacks(mapper);
        DependencyService dependencies = new DependencyService(stacks, files, sandbox, properties);
        FileChangeRepository changes = mock(FileChangeRepository.class);
        when(changes.save(any())).thenAnswer(call -> call.getArgument(0));
        ChangeSetService changeSets = new ChangeSetService(changes, mock(AgentRunRepository.class), mapper, properties, files, sandbox);
        RuntimeCheckService runtime = new RuntimeCheckService(changeSets, stacks, files, sandbox, dependencies, properties, mapper);
        ProjectValidationService validation = new ProjectValidationService(changeSets, stacks, properties, files, sandbox,
                dependencies, runtime);

        UUID runId = UUID.randomUUID();
        String stage = changeSets.start(runId, projectId);
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Write the site", "Validate"), "it");
        AgentContext context = new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null, new ProjectContextTrace(), runId, plan, files);
        changeSets.create(context, "index.html", "<!doctype html><div id=\"root\"></div><script src=\"app.js\"></script>");
        changeSets.create(context, "app.js", "function renderMenu(items) { return items.map((item) => '<li>' + item + '</li>').join(''); }\n"
                + "document.getElementById('root').innerHTML = renderMenu(window.menuItems);\n");

        ValidationReport broken = validation.validateAll(context);

        assertFalse(broken.changeValidationPassed());
        ProjectDiagnostic problem = broken.introducedDiagnostics().stream()
                .filter(item -> "RUNTIME_EXCEPTION".equals(item.code())).findFirst().orElseThrow(() -> new AssertionError(broken.introducedDiagnostics()));
        assertTrue(problem.message().contains("Cannot read properties of undefined"), problem.message());
        assertTrue(problem.message().contains("renderMenu"), "the failing function is named: " + problem.message());

        String version = files.version(projectId, stage, "app.js");
        changeSets.patch(context, "app.js", version, "renderMenu(window.menuItems)", "renderMenu(['Espresso', 'Latte'])");
        ValidationReport repaired = validation.validateAll(context);

        assertTrue(repaired.changeValidationPassed(), repaired.introducedDiagnostics().toString());
        assertTrue(repaired.summary().contains("RUNTIME"), repaired.summary());
        changeSets.commit(context, AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.BUILD).build());
        assertEquals("<!doctype html><div id=\"root\"></div><script src=\"app.js\"></script>", files.readText(projectId, "index.html"));
    }

    private static void run(String... command) throws Exception {
        List<String> args = new ArrayList<>(List.of("wsl.exe", "-d", DISTRO, "-e"));
        args.addAll(List.of(command));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }
}
