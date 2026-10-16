package com.code.pal.agent.starter;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theViteStarterHasTheSetupFilesButNotTheApp() {
        Map<String, String> files = Starter.VITE_REACT.files("Tip <Calc> & \"more\"");
        assertEquals(List.of(".gitignore", ".nvmrc", "starter-runtime.json", "package.json", "package-lock.json", "tsconfig.json", "vite.config.ts", "index.html", "src/main.tsx",
                "src/index.css", "src/router.tsx"), List.copyOf(files.keySet()));
        assertTrue(files.get("index.html").contains("<title>Tip &lt;Calc&gt; &amp; &quot;more&quot;</title>"));
        assertTrue(files.get("index.html").contains("src=\"/src/main.tsx\""));
        assertTrue(files.get("src/index.css").startsWith("@import \"tailwindcss\";"));
        assertTrue(files.get("vite.config.ts").contains("plugins: [react(), tailwindcss()]"));
        assertTrue(files.get("src/main.tsx").contains("import App from './App'"), "the model writes src/App.tsx");
        assertTrue(files.get(".gitignore").contains("node_modules"));
    }

    @Test
    void everyStarterPinsExactVersions() {
        for (Starter starter : Starter.values()) {
            starter.files(null).forEach((path, content) -> {
                if (!path.endsWith("package.json")) return;
                JsonNode manifest = json.readTree(content);
                for (String section : List.of("dependencies", "devDependencies")) {
                    manifest.path(section).properties().forEach(entry ->
                            assertTrue(entry.getValue().asString().matches("\\d+\\.\\d+\\.\\d+"),
                                    path + " " + entry.getKey() + " " + entry.getValue()));
                }
            });
        }
    }

    @Test void lockfilesMatchManifestsAndSelectCleanInstallWithPinnedRuntime() {
        for (Starter starter : Starter.values()) {
            var files = starter.files(null);
            var runtime = json.readTree(files.get("starter-runtime.json"));
            assertEquals(Starter.RUNTIME_IMAGE, runtime.path("image").asString());
            assertEquals(Starter.TEMPLATE_VERSION, runtime.path("templateVersion").asString());
            assertEquals(runtime.path("node").asString(), files.get(".nvmrc").strip());
            var project = com.code.pal.agent.workspace.LocalProject.create();
            files.forEach(project::write);
            for (String path : files.keySet()) {
                if (!path.endsWith("package.json")) continue;
                var manifest = json.readTree(files.get(path));
                var lock = json.readTree(files.get(path.replace("package.json", "package-lock.json")));
                assertEquals(3, lock.path("lockfileVersion").asInt());
                var root = lock.path("packages").path("");
                for (String key : List.of("dependencies", "devDependencies", "engines")) assertEquals(manifest.path(key), root.path(key));
                assertEquals("npm@" + runtime.path("npm").asString(), manifest.path("packageManager").asString());
                assertEquals(runtime.path("node").asString(), manifest.path("engines").path("node").asString());
                String packageRoot = path.equals("package.json") ? "." : path.substring(0, path.lastIndexOf('/'));
                assertEquals("ci", new com.code.pal.agent.stack.NodePack(json).installCommand(project.files, project.projectId, packageRoot).get(1));
            }
        }
        assertEquals(Starter.RUNTIME_IMAGE, new com.code.pal.agent.config.AgentProperties().getSandbox().getImage());
    }

    @Test
    void theMernStarterPutsTheAppInClientAndAddsTheServer() {
        Map<String, String> files = Starter.MERN.files("Todos");
        assertTrue(files.keySet().containsAll(List.of(".gitignore", "client/package.json", "client/index.html",
                "client/src/main.tsx", "server/package.json", "server/tsconfig.json", "server/src/index.ts")), files.keySet().toString());
        assertFalse(files.containsKey("package.json"), "no root package: each package installs on its own");
        assertTrue(files.get("client/vite.config.ts").contains("'/api': 'http://localhost:3000'"));
        String server = files.get("server/src/index.ts");
        assertTrue(server.contains("/api/health") && server.contains("process.env.PORT") && server.contains("process.env.MONGODB_URI"));
        JsonNode manifest = json.readTree(files.get("server/package.json"));
        assertEquals("dist/index.js", manifest.path("main").asString());
        assertTrue(manifest.path("type").isMissingNode(), "CommonJS, as the server's tsconfig compiles it");
    }

    @Test
    void unknownStacksAreRefused() {
        assertEquals(Optional.of(Starter.MERN), Starter.of("mern"));
        assertEquals(Optional.empty(), Starter.of("static"));
        assertEquals(Optional.empty(), Starter.of(""));
    }

    @Test
    void aBlankTitleBecomesApp() {
        assertTrue(Starter.VITE_REACT.files("  ").get("index.html").contains("<title>App</title>"));
        assertTrue(Starter.VITE_REACT.files("x".repeat(200)).get("index.html").contains("<title>" + "x".repeat(60) + "</title>"));
    }
}
