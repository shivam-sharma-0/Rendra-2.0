package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned SDK, native controls and offline Chromium through the production private Supabase relay. */
@EnabledIfSystemProperty(named = "codepal.supabase.sdk.it", matches = "true")
class SupabaseSdkBrowserIT {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sdkSignupLoginRejectionSessionReloadAndServerCrudWorkThroughTheOfflineBrowser() throws Exception {
        var report = run(false);
        assertTrue(report.path("acceptance").valueStream().allMatch(action -> action.path("passed").asBoolean()), report.toString());
    }

    @Test
    void anSdkUiWithABrokenDeleteCannotPassTheSameBrowserRecipe() throws Exception {
        var report = run(true);
        assertTrue(report.path("acceptance").valueStream().filter(action -> !action.path("id").asString().equals("deleted"))
                .allMatch(action -> action.path("passed").asBoolean()), report.toString());
        assertTrue(report.path("acceptance").valueStream().anyMatch(action -> action.path("id").asString().equals("deleted")
                && !action.path("passed").asBoolean() && !action.path("prerequisiteFailed").asBoolean()), report.toString());
    }

    private JsonNode run(boolean brokenDelete) throws Exception {
        Path allowed = Path.of("target/codex-supabase-sdk-it").toAbsolutePath().normalize();
        Path root = Path.of(System.getProperty("codepal.supabase.sdk.directory")).toAbsolutePath().normalize();
        assertTrue(root.startsWith(allowed) && !root.equals(allowed), "A dedicated owned SDK fixture directory is required");
        var policy = mapper.readTree(Files.readString(Path.of("scripts/fixtures/supabase-sdk.json")));
        var receipt = mapper.readTree(Files.readString(root.resolve("sdk-receipt.json")));
        assertEquals(policy.path("version").asString(), receipt.path("version").asString());
        assertEquals(policy.path("integrity").asString(), receipt.path("integrity").asString());
        assertEquals(receipt.path("sha256").asString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("supabase.js")))));
        String owner = root.getFileName().toString();
        assertTrue(owner.matches("codepal-codex-sdk-[a-f0-9]{12}"));
        Path scenario = root.resolve((brokenDelete ? "broken-delete-" : "working-") + UUID.randomUUID());
        Path site = Files.createDirectories(scenario.resolve("site"));
        Files.copy(root.resolve("supabase.js"), site.resolve("supabase.js"));
        var properties = new AgentProperties();
        properties.getSupabaseValidation().setEnabled(true);
        properties.getSandbox().setDockerCommand(System.getProperty("codepal.docker.command", "docker"));
        properties.getSandbox().setDockerCommandArgs(System.getProperty("codepal.docker.args", ""));
        var docker = new DockerCommands(properties);
        String container = owner + "-browser";
        String sql = resource("owned-tasks.sql");
        String email = "sdk-" + UUID.randomUUID().toString().replace("-", "") + "@example.invalid";
        String password = UUID.randomUUID() + "-Valid!8";
        String title = "SDK task " + UUID.randomUUID();
        try (var scratch = new DockerSupabaseScratch(properties, mapper).open(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new SupabaseScratch.Migration("tasks.sql", sql)));
                var relay = new BrowserApiRelay(scenario.resolve("relay"), scratch.endpoint(), BrowserApiRelay.Scope.SUPABASE)) {
            String fixture = mapper.writeValueAsString(Map.of("url", DockerSupabaseScratch.BROWSER_ORIGIN,
                    "key", scratch.publicKey(), "brokenDelete", brokenDelete));
            Files.writeString(site.resolve("index.html"), resource("sdk-ui.html").replace("__FIXTURE__", fixture));
            var actions = actions(email, password, title);
            String acceptance = Base64.getEncoder().encodeToString(mapper.writeValueAsString(Map.of("schema", 1, "actions", actions)).getBytes(StandardCharsets.UTF_8));
            var image = docker.run(List.of("image", "inspect", policy.path("browserImage").asString(), "--format", "{{.Id}}"), Duration.ofSeconds(10), null, 4096);
            assertTrue(image.success() && image.stdout().trim().matches("sha256:[a-f0-9]{64}"), "Cached browser fixture image required");
            var command = new ArrayList<>(List.of("run", "--rm", "--name", container, "--label", "com.codepal.codex.sdk-it=" + owner,
                    "--pull", "never", "--network", "none", "--user", "1000:1000", "--cpus", "1", "--memory", "512m",
                    "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--mount", "type=bind,source=" + linux(site) + ",target=/site,readonly",
                    "--mount", "type=bind,source=" + linux(scenario.resolve("relay")) + ",target=/relay",
                    "-e", "CODEPAL_SUPABASE_ORIGIN=" + DockerSupabaseScratch.BROWSER_ORIGIN));
            for (String module : List.of("probe.mjs", "relay.mjs", "outcomes.mjs")) {
                command.addAll(List.of("--mount", "type=bind,source=" + linux(Path.of("src/main/resources/sandbox/browser/" + module).toAbsolutePath())
                        + ",target=/opt/codepal/" + module + ",readonly"));
            }
            command.addAll(List.of("--entrypoint", "node", image.stdout().trim(), "/opt/codepal/probe.mjs", "/site", "300", acceptance, "/relay"));
            var result = docker.run(command, Duration.ofSeconds(60), null, 65536);
            assertTrue(result.success(), "Offline SDK browser command failed");
            var report = mapper.readTree(result.stdout().lines().filter(line -> line.startsWith("{")).reduce((first, last) -> last).orElseThrow());
            Files.writeString(scenario.resolve("browser.json"), mapper.writeValueAsString(report));
            assertTrue(report.path("loaded").asBoolean(), report.toString());
            assertTrue(report.path("issues").isEmpty(), report.toString());
            assertEquals(actions.size(), report.path("acceptance").size(), report.toString());
            return report;
        } finally {
            var remaining = docker.run(List.of("ps", "-aq", "--filter", "name=^/" + container + "$"), Duration.ofSeconds(10), null, 4096);
            assertTrue(remaining.success(), "SDK browser cleanup could not be inspected");
            if (!remaining.stdout().isBlank()) {
                var labels = docker.run(List.of("inspect", "--format", "{{json .Config.Labels}}", container), Duration.ofSeconds(10), null, 4096);
                assertTrue(labels.success() && mapper.readTree(labels.stdout()).path("com.codepal.codex.sdk-it").asString().equals(owner));
                assertTrue(docker.run(List.of("rm", "-f", container), Duration.ofSeconds(10), null, 4096).success(), "SDK browser cleanup failed");
            }
        }
    }

    private static List<BrowserAction> actions(String email, String password, String title) {
        var result = new ArrayList<BrowserAction>();
        result.add(new BrowserAction("email", BrowserKind.FILL, "#email", email));
        result.add(new BrowserAction("password", BrowserKind.FILL, "#password", password));
        result.add(new BrowserAction("signup", BrowserKind.CLICK, "#signup", null));
        result.add(new BrowserAction("signed_up", BrowserKind.ASSERT_TEXT, "#session", "Signed in"));
        result.add(new BrowserAction("title", BrowserKind.FILL, "#title", title));
        result.add(new BrowserAction("save", BrowserKind.CLICK, "#save", null));
        result.add(new BrowserAction("created", BrowserKind.ASSERT_TEXT, "#items", title));
        result.add(new BrowserAction("reload", BrowserKind.RELOAD, null, null));
        result.add(new BrowserAction("session", BrowserKind.ASSERT_TEXT, "#session", "Signed in"));
        result.add(new BrowserAction("persisted", BrowserKind.ASSERT_TEXT, "#items", title));
        result.add(new BrowserAction("logout", BrowserKind.CLICK, "#signout", null));
        result.add(new BrowserAction("signed_out", BrowserKind.ASSERT_TEXT, "#session", "Signed out"));
        result.add(new BrowserAction("email_again", BrowserKind.FILL, "#email", email));
        result.add(new BrowserAction("bad_password", BrowserKind.FILL, "#password", "Invalid-" + password));
        result.add(new BrowserAction("bad_login", BrowserKind.CLICK, "#signin", null));
        result.add(new BrowserAction("rejected", BrowserKind.ASSERT_TEXT, "#error", "Request failed"));
        result.add(new BrowserAction("correct_password", BrowserKind.FILL, "#password", password));
        result.add(new BrowserAction("login", BrowserKind.CLICK, "#signin", null));
        result.add(new BrowserAction("logged_in", BrowserKind.ASSERT_TEXT, "#session", "Signed in"));
        result.add(new BrowserAction("still_saved", BrowserKind.ASSERT_TEXT, "#items", title));
        result.add(new BrowserAction("delete", BrowserKind.CLICK, "#items button", null));
        result.add(new BrowserAction("deleted", BrowserKind.ASSERT_COUNT, "#items li", "0"));
        return List.copyOf(result);
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/supabase/" + name)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static String linux(Path path) throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return path.toAbsolutePath().toString();
        var process = new ProcessBuilder("wsl.exe", "-e", "wslpath", "-u", path.toAbsolutePath().toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Fixture path conversion timed out"); }
        assertEquals(0, process.exitValue(), "Fixture path conversion failed");
        return new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8).trim();
    }
}
