package com.code.pal.agent.starter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.code.pal.agent.contract.StaticAssets;

/**
 * Tested setup files for a new project, written by code instead of the model: the files that decide
 * whether a project installs, type-checks and builds at all (package versions that work together, the
 * TypeScript, Vite and Tailwind configuration, the entry files). The model writes the app on top.
 * Versions are pinned to a set checked with the sandbox's own install, type-check and build commands.
 */
public enum Starter {

    VITE_REACT("vite-react"),
    STATIC_SITE("static-site"),
    MERN("mern");

    private static final String BASE = "starters/";
    public static final String TEMPLATE_VERSION = "2026-10-17.1";
    public static final String RUNTIME_IMAGE = "node@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c";
    private static final List<String> VITE_FILES = List.of("package.json", "package-lock.json", "tsconfig.json", "vite.config.ts",
            "index.html", "src/main.tsx", "src/index.css", "src/router.tsx");
    private static final List<String> SERVER_FILES = List.of("server/package.json", "server/package-lock.json", "server/tsconfig.json", "server/src/index.ts");
    private static final int MAX_TITLE = 60;

    private final String id;

    Starter(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<Starter> of(String id) {
        for (Starter starter : values()) if (starter.id.equals(id)) return Optional.of(starter);
        return Optional.empty();
    }

    /** The files to write, by project path, in order; {@code title} becomes the page title. */
    public Map<String, String> files(String title) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(".gitignore", read("vite-react/gitignore"));
        files.put(".nvmrc", "22.23.3\n");
        files.put("starter-runtime.json", read("runtime.json"));
        if (this == STATIC_SITE) {
            files.putAll(StaticAssets.files(UUID.randomUUID()));
            return files;
        }
        String prefix = this == MERN ? "client/" : "";
        for (String path : VITE_FILES) files.put(prefix + path, read("vite-react/" + path));
        files.put(prefix + "src/design-system.css", read("design-system.css"));
        if (this == MERN) {
            // The client sends /api to the server in local development; the preview host forwards it.
            files.put("client/vite.config.ts", read("mern/client/vite.config.ts"));
            for (String path : SERVER_FILES) files.put(path, read("mern/" + path));
        }
        files.replaceAll((path, content) -> path.endsWith("index.html") ? content.replace("{{title}}", escapeHtml(title(title))) : content);
        return files;
    }

    /** What the model writes next, returned with the tool result. */
    public String next() {
        return read(id + "/next.md").strip();
    }

    private static String title(String title) {
        String value = title == null || title.isBlank() ? "App" : title.replaceAll("\\s+", " ").strip();
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE).strip();
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String read(String path) {
        try (InputStream in = Starter.class.getClassLoader().getResourceAsStream(BASE + path)) {
            if (in == null) throw new IllegalStateException("Missing starter file " + BASE + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
