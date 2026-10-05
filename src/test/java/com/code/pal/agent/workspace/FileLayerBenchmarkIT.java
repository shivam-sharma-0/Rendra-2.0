package com.code.pal.agent.workspace;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.FileChangeRepository;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.preview.PreviewService;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.impl.ListFilesTool;
import com.code.pal.agent.tool.impl.ReadFileTool;
import com.code.pal.agent.tool.impl.SearchCodeTool;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Times the agent's file-heavy operations on a realistic project (Vite/React, ~136 source files plus
 * a 5000-file node_modules) in WSL ext4, through the production Docker + WSL-share stack. Opt-in:
 * {@code mvnw test -Dtest=FileLayerBenchmarkIT -Dcodepal.bench=true}; the fixture comes from
 * {@code -Dcodepal.bench.fixture} (Linux path, default /home/shivam/codepal-bench/fixture).
 */
@EnabledIfSystemProperty(named = "codepal.bench", matches = "true")
class FileLayerBenchmarkIT {

    private static final String DISTRO = System.getProperty("codepal.wsl.distro", "Ubuntu-24.04");
    private static final String LINUX_BASE = System.getProperty("codepal.wsl.base", "/home/shivam/codepal-it/workspaces");
    private static final String FIXTURE = System.getProperty("codepal.bench.fixture", "/home/shivam/codepal-bench/fixture");
    private static final int ROUNDS = 3;

    private final AgentProperties properties = properties();
    private final UUID projectId = UUID.randomUUID();
    private final Map<String, Double> results = new LinkedHashMap<>();

    @AfterEach
    void cleanUp() throws Exception {
        wsl("docker", "rm", "-f", properties.getSandbox().getContainerPrefix() + projectId);
        wsl("rm", "-rf", LINUX_BASE + "/" + projectId);
    }

    @Test
    void fileHeavyAgentOperations() throws Exception {
        wsl("mkdir", "-p", LINUX_BASE);
        wsl("cp", "-a", FIXTURE, LINUX_BASE + "/" + projectId);
        WorkspaceStorage storage = new WorkspaceStorage(properties);
        DockerProjectEnvironment sandbox = new DockerProjectEnvironment(properties, storage);
        HostProjectFiles files = new HostProjectFiles(storage);
        sandbox.ensure(projectId);                          // container start is not what is measured

        ProjectContextService contexts = new ProjectContextService(properties, new ObjectMapper());
        var context = contexts.build(files, projectId, ".", "Add a settings page", List.of(), List.of());
        assertTrue(context.projectMap().scannedFileCount() >= 130 && context.projectMap().scannedFileCount() < 200,
                "node_modules and dist must not be scanned: " + context.projectMap().scannedFileCount());
        time("context build (project map)", () -> contexts.build(files, projectId, ".", "Add a settings page", List.of(), List.of()));

        AgentContext chat = new AgentContext(projectId, 7L, ProjectType.CHAT, ".", files);
        List<String> sources = new ArrayList<>();
        for (int i = 1; i <= 20; i++) sources.add("src/components/Widget" + i + ".tsx");
        ReadFileTool read = new ReadFileTool();
        time("readFile x20", () -> {
            AgentContext fresh = new AgentContext(projectId, 7L, ProjectType.CHAT, ".", files);
            for (String path : sources) read.execute(Map.of("path", path), fresh);
            return null;
        });
        time("listFiles (src)", () -> new ListFilesTool().execute(Map.of("path", "src"), chat));
        assertTrue((Integer) new SearchCodeTool().execute(Map.of("query", "useState", "maxResults", 100), chat).get("matchCount") >= 80);
        time("searchCode (useState)", () -> new SearchCodeTool().execute(Map.of("query", "useState", "maxResults", 100), chat));
        WorkspaceBrowser browser = new WorkspaceBrowser(files);
        time("files panel tree", () -> browser.tree(projectId));
        time("files panel content", () -> browser.read(projectId, "src/App.tsx"));
        PreviewService preview = new PreviewService(files, mock(com.code.pal.agent.preview.SiteBuildService.class),
                mock(com.code.pal.agent.preview.PublishedSiteService.class));
        time("preview index.html", () -> preview.serve(projectId, "index.html"));
        time("preview asset", () -> preview.serve(projectId, "assets/chunk7.js"));

        FileChangeRepository changes = mock(FileChangeRepository.class);
        when(changes.save(any())).thenAnswer(call -> call.getArgument(0));
        ChangeSetService changeSets = new ChangeSetService(changes, mock(AgentRunRepository.class),
                new ObjectMapper(), properties, files, sandbox);
        time("change set: start + 5 writes + commit", () -> {
            UUID runId = UUID.randomUUID();
            String stage = changeSets.start(runId, projectId);
            AgentPlanState plan = new AgentPlanState();
            plan.revise(List.of("Write", "Validate"), "bench");
            AgentContext build = new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null,
                    new ProjectContextTrace(), runId, plan, files);
            for (int i = 0; i < 5; i++) changeSets.create(build, "src/pages/Page" + runId.toString().substring(0, 8) + i + ".tsx", "export const p = " + i + ";");
            changeSets.recordProjectValidation(build, true, List.of());
            changeSets.commit(build, AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.BUILD).build());
            return null;
        });

        StringBuilder report = new StringBuilder("label=" + System.getProperty("codepal.bench.label", "current") + "\n");
        results.forEach((name, ms) -> report.append(String.format("%-40s %8.0f ms%n", name, ms)));
        System.out.println(report);
        Path out = Path.of("target", "bench-" + System.getProperty("codepal.bench.label", "current") + ".txt");
        Files.writeString(out, report);
    }

    /** Median of {@link #ROUNDS} runs, in milliseconds. */
    private void time(String name, Callable<?> operation) throws Exception {
        List<Double> samples = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            long started = System.nanoTime();
            operation.call();
            samples.add((System.nanoTime() - started) / 1e6);
        }
        samples.sort(Double::compare);
        results.put(name, samples.get(samples.size() / 2));
    }

    private static AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setRequired(true);
        properties.getSandbox().setDockerCommand("wsl.exe");
        properties.getSandbox().setDockerCommandArgs("-e docker");
        properties.setWorkspaceBasePath("//wsl.localhost/" + DISTRO + LINUX_BASE);
        return properties;
    }

    private static void wsl(String... command) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("wsl.exe", "-d", DISTRO, "-e"));
        args.addAll(List.of(command));
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }
}
