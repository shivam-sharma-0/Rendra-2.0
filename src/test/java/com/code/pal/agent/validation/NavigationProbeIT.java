package com.code.pal.agent.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the production probe's action expression under Node; no provider, browser or package install. */
@EnabledIfSystemProperty(named = "codepal.router.it", matches = "true")
class NavigationProbeIT {
    @Test
    void duplicateLinksPassExistenceWhileExplicitCountsAndFirstClickKeepTheirMeaning(@TempDir Path scratch) throws Exception {
        Path driver = scratch.resolve("navigation-probe.cjs");
        try (var resource = getClass().getClassLoader().getResourceAsStream("validation/navigation-probe.cjs")) {
            assertNotNull(resource);
            Files.copy(resource, driver);
        }
        Path probe = Path.of("src/main/resources/sandbox/browser/probe.mjs").toAbsolutePath();
        Path log = scratch.resolve("navigation-probe.log");
        var process = new ProcessBuilder("node", driver.toString(), probe.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Navigation probe expression verification timed out");
        }
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
}
