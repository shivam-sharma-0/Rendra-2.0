package com.code.pal.agent.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual generated TSX through the installed TypeScript and React server renderer; no package install. */
@EnabledIfSystemProperty(named = "codepal.router.it", matches = "true")
class ContractScaffoldRouterIT {
    @Test
    void unknownPathsRenderNotFoundAndKnownPathsRenderTheirDeclaredPage(@TempDir Path scratch) throws Exception {
        var mapper = new ObjectMapper();
        var contract = ContractScaffoldRouterTest.routed();
        var output = new ContractScaffold(mapper).render(contract, "Fixture", path -> null);
        var fixture = Map.of("app", output.get("src/App.tsx"), "routes", output.get(contract.clientRoot() + "routes.ts"),
                "home", "export default function Home() { return <h1>Known home</h1> }",
                "settings", "export default function Settings() { return <h1>Known settings</h1> }");
        Files.writeString(scratch.resolve("fixture.json"), mapper.writeValueAsString(fixture));
        Path modules = Path.of(System.getProperty("codepal.router.node-modules", "frontend/node_modules")).toAbsolutePath().normalize();
        Path driver = scratch.resolve("router.cjs");
        try (var resource = getClass().getClassLoader().getResourceAsStream("contract/router.cjs")) {
            assertNotNull(resource);
            Files.copy(resource, driver);
        }
        Path log = scratch.resolve("router.log");
        var process = new ProcessBuilder("node", driver.toString(), modules.toString(), scratch.resolve("fixture.json").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Generated router did not finish within 30 seconds");
        }
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
}
