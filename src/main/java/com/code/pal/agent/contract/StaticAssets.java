package com.code.pal.agent.contract;

import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned, fixed static compiler policy. Project text cannot select commands or plugins. */
public final class StaticAssets {
    public static final String MARKER = "starter-static.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEMPLATE = "static-2026-10-16.1";
    private StaticAssets() {
    }

    public static Map<String, String> files(UUID contract) {
        return Map.of("package.json", resource("package.json"), "package-lock.json", resource("package-lock.json"),
                "design-system.css", read("starters/design-system.css"),
                MARKER, JSON.writeValueAsString(Map.of("schemaVersion", 1, "contractId", contract.toString(),
                        "templateVersion", TEMPLATE, "compilerHash", revision())));
    }

    public static String revision() {
        return ContractStore.hash(TEMPLATE + resource("build.mjs") + resource("package.json") + resource("package-lock.json"));
    }

    public static void require(ProjectFiles files, UUID project, String root) {
        try {
            var marker = JSON.readTree(new String(files.readBytes(project, root, MARKER, 4_096), StandardCharsets.UTF_8));
            Set<?> keys = JSON.readValue(marker.toString(), Map.class).keySet();
            if (!keys.equals(Set.of("schemaVersion", "contractId", "templateVersion", "compilerHash"))
                    || marker.path("schemaVersion").asInt() != 1 || !TEMPLATE.equals(marker.path("templateVersion").asString())
                    || !revision().equals(marker.path("compilerHash").asString())) {
                throw new IllegalArgumentException("Static compiler identity changed");
            }
            UUID.fromString(marker.path("contractId").asString());
            for (String name : List.of("package.json", "package-lock.json")) {
                String actual = new String(files.readBytes(project, root, name, 512_000), StandardCharsets.UTF_8);
                if (!JSON.readTree(actual).equals(JSON.readTree(resource(name)))) {
                    throw new IllegalArgumentException("Static compiler manifests changed");
                }
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Static asset build needs its reviewed compiler and pinned manifests", invalid);
        }
    }

    public static List<String> command() {
        return List.of("node", ProjectPaths.INTERNAL + "/static/compiler.mjs");
    }

    public static void prepare(ProjectFiles files, UUID project, String root) {
        require(files, project, root);
        // Send a path through Windows/WSL, never multiline JavaScript with quote-sensitive regexes.
        files.writeText(project, root, ProjectPaths.INTERNAL + "/static/compiler.mjs", resource("build.mjs"));
    }

    private static String resource(String name) {
        return read("starters/static-site/" + name);
    }

    private static String read(String path) {
        try (var stream = StaticAssets.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing trusted static resource");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
