package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.HostProjectFiles;
import com.code.pal.agent.workspace.WorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionBaselineTest {
    private static final String GOOD_HEAD = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta content="Fresh bread every morning" name="description">
            <link rel="icon" href="/favicon.svg"><title>Solar Bakery</title></head><body></body></html>
            """;

    @TempDir
    Path workspace;
    private final UUID project = UUID.randomUUID();
    private HostProjectFiles files;
    private ProductionBaseline baseline;

    private void open() {
        var props = new AgentProperties();
        props.setWorkspaceBasePath(workspace.toString());
        files = new HostProjectFiles(new WorkspaceStorage(props));
        baseline = new ProductionBaseline(files, new ObjectMapper());
    }

    @Test
    void aCompleteStaticPagePasses() {
        open();
        files.writeText(project, "index.html", GOOD_HEAD);
        assertEquals(List.of(), baseline.check(project, "."));
    }

    @Test
    void aBarePageListsEveryMissingItem() {
        open();
        files.writeText(project, "index.html", "<html><head><title> </title><meta name=\"description\" content=\"\"></head></html>");
        assertEquals(List.of("BASELINE_TITLE", "BASELINE_DESCRIPTION", "BASELINE_VIEWPORT", "BASELINE_FAVICON"), codes("."));
    }

    @Test
    void aFaviconFileCountsWithoutALinkTag() {
        open();
        files.writeText(project, "index.html", GOOD_HEAD.replace("<link rel=\"icon\" href=\"/favicon.svg\">", ""));
        files.writeText(project, "public/favicon.svg", "<svg/>");
        assertEquals(List.of(), baseline.check(project, "."));
    }

    @Test
    void clientCodeMayReadOnlyPublicViteVariablesAndNoServerKeys() {
        open();
        files.writeText(project, "index.html", GOOD_HEAD);
        files.writeText(project, "src/ok.ts", "const url = import.meta.env.VITE_SUPABASE_URL; if (import.meta.env.DEV) {}");
        files.writeText(project, "src/env.ts", "const key = import.meta.env.OPENAI_API_KEY;");
        files.writeText(project, "src/admin.ts", "createClient(url, 'service_role-key')");
        files.writeText(project, "server/index.js", "process.env.SECRET; const k = 'service_role'");
        var issues = baseline.check(project, ".");
        assertEquals(List.of("BASELINE_CLIENT_SECRET", "BASELINE_CLIENT_SECRET"), issues.stream().map(ProjectDiagnostic::code).toList());
        assertTrue(issues.stream().anyMatch(issue -> issue.message().contains("OPENAI_API_KEY")));
        assertTrue(issues.stream().noneMatch(issue -> issue.file().startsWith("server")));
    }

    @Test
    void aRoutedAppNeedsACatchAllNotFoundRoute() {
        open();
        files.writeText(project, "index.html", GOOD_HEAD);
        files.writeText(project, "package.json", "{\"dependencies\":{\"react-router-dom\":\"7.0.0\"}}");
        files.writeText(project, "src/App.tsx", "<Route path=\"/\" element={<Home/>} />");
        assertEquals(List.of("BASELINE_NOT_FOUND_ROUTE"), codes("."));

        files.writeText(project, "src/App.tsx", "<Route path=\"/\" element={<Home/>} /><Route path=\"*\" element={<NotFound/>} />");
        assertEquals(List.of(), baseline.check(project, "."));
    }

    @Test
    void aMernClientIsCheckedAndAnApiOnlyProjectIsOutOfScope() {
        open();
        files.writeText(project, "server/package.json", "{}");
        assertEquals(List.of(), baseline.check(project, "."), "no web entry page");

        files.writeText(project, "client/package.json", "{}");
        files.writeText(project, "client/index.html", "<html><head></head></html>");
        assertEquals("client/index.html", baseline.check(project, ".").getFirst().file());
    }

    private List<String> codes(String root) {
        return baseline.check(project, root).stream().map(ProjectDiagnostic::code).toList();
    }
}
