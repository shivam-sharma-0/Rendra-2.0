package com.code.pal.agent.validation;

import com.code.pal.agent.workspace.ProjectFiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic production checklist for a web app's files: page title, meta description, viewport,
 * favicon, no server secrets in client code and a not-found route for routed apps. Browser-only
 * checks (console errors, 375 px overflow) stay with the runtime check.
 */
@Component
public class ProductionBaseline {
    static final String CATEGORY = "production-baseline";
    private static final int MAX_FILE_BYTES = 256_000;
    private static final int MAX_SCANNED_FILES = 400;
    private static final Set<String> SKIPPED = Set.of("node_modules", "dist", "build", ".git", ".codepal", "server",
            "supabase");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".js", ".jsx", ".ts", ".tsx", ".html", ".vue", ".mjs");
    private static final Pattern TITLE = Pattern.compile("<title>\\s*[^<\\s][^<]*</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ICON_LINK = Pattern.compile("<link\\b[^>]*rel\\s*=\\s*[\"'][^\"']*icon[^\"']*[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_ENV = Pattern.compile(
            "import\\.meta\\.env\\.(?!VITE_|MODE\\b|DEV\\b|PROD\\b|BASE_URL\\b|SSR\\b)([A-Z_][A-Z0-9_]*)");
    private static final Pattern SECRET_LITERAL = Pattern.compile("sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}"
            + "|-----BEGIN [A-Z ]*PRIVATE KEY-----|service_role|SUPABASE_SERVICE_ROLE");
    private static final Pattern CATCH_ALL_ROUTE = Pattern.compile("path\\s*[=:]\\s*\\{?\\s*[\"']\\*[\"']");

    private final ProjectFiles files;
    private final ObjectMapper mapper;

    public ProductionBaseline(ProjectFiles files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    /** Missing items for the app under {@code root}; empty when it has no web entry page (API-only or legacy). */
    public List<ProjectDiagnostic> check(UUID projectId, String root) {
        String web = files.regularFile(projectId, root, "client/package.json") ? "client" : ".";
        String indexPath = path(web, "index.html");
        if (!files.regularFile(projectId, root, indexPath) || files.size(projectId, root, indexPath) > MAX_FILE_BYTES) {
            return List.of();
        }
        String index = files.readText(projectId, root, indexPath);
        List<ProjectDiagnostic> missing = new ArrayList<>();
        if (!TITLE.matcher(index).find()) {
            missing.add(issue(indexPath, "BASELINE_TITLE", "Give the page a descriptive <title>"));
        }
        if (!hasMeta(index, "description", true)) {
            missing.add(issue(indexPath, "BASELINE_DESCRIPTION", "Add <meta name=\"description\" content=\"...\">"));
        }
        if (!hasMeta(index, "viewport", false)) {
            missing.add(issue(indexPath, "BASELINE_VIEWPORT",
                    "Add <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"));
        }
        if (!ICON_LINK.matcher(index).find() && !hasFaviconFile(projectId, root, web)) {
            missing.add(issue(indexPath, "BASELINE_FAVICON", "Add a favicon (<link rel=\"icon\" ...> with an icon file)"));
        }
        boolean routed = routed(projectId, root, web);
        boolean catchAll = false;
        int scanned = 0;
        for (var entry : files.list(projectId, path(root, web), 6, SKIPPED)) {
            if (entry.directory() || entry.size() > MAX_FILE_BYTES || !source(entry.path()) || scanned++ >= MAX_SCANNED_FILES) {
                continue;
            }
            String relative = path(web, entry.path());
            String text = files.readText(projectId, root, relative);
            var env = PRIVATE_ENV.matcher(text);
            if (env.find()) {
                missing.add(issue(relative, "BASELINE_CLIENT_SECRET", "Client code reads " + env.group(1)
                        + "; only public VITE_ variables may reach the browser"));
            } else if (SECRET_LITERAL.matcher(text).find()) {
                missing.add(issue(relative, "BASELINE_CLIENT_SECRET",
                        "Client code contains a server secret or service-role key; keep secrets on the server"));
            }
            catchAll |= routed && CATCH_ALL_ROUTE.matcher(text).find();
        }
        if (routed && !catchAll) {
            missing.add(issue(path(web, "src"), "BASELINE_NOT_FOUND_ROUTE",
                    "Add a not-found page on a catch-all route (path=\"*\")"));
        }
        return missing;
    }

    private static boolean hasMeta(String index, String name, boolean needsContent) {
        var tags = META.matcher(index);
        while (tags.find()) {
            String tag = tags.group().toLowerCase(Locale.ROOT);
            if (tag.matches("(?s).*\\bname\\s*=\\s*[\"']" + name + "[\"'].*")
                    && (!needsContent || tag.matches("(?s).*\\bcontent\\s*=\\s*[\"']\\s*[^\"'\\s][^\"']*[\"'].*"))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFaviconFile(UUID projectId, String root, String web) {
        for (String name : List.of("favicon.ico", "favicon.svg", "favicon.png", "public/favicon.ico", "public/favicon.svg",
                "public/favicon.png")) {
            if (files.regularFile(projectId, root, path(web, name))) {
                return true;
            }
        }
        return false;
    }

    private boolean routed(UUID projectId, String root, String web) {
        String manifest = path(web, "package.json");
        if (!files.regularFile(projectId, root, manifest) || files.size(projectId, root, manifest) > MAX_FILE_BYTES) {
            return false;
        }
        try {
            var json = mapper.readTree(files.readText(projectId, root, manifest));
            return json.path("dependencies").has("react-router-dom") || json.path("dependencies").has("react-router");
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean source(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 && SOURCE_EXTENSIONS.contains(path.substring(dot).toLowerCase(Locale.ROOT));
    }

    private static String path(String base, String child) {
        return base.equals(".") ? child : base + "/" + child;
    }

    private static ProjectDiagnostic issue(String path, String code, String message) {
        return new ProjectDiagnostic(path, null, null, DiagnosticSeverity.ERROR, message, CATEGORY, code);
    }
}
