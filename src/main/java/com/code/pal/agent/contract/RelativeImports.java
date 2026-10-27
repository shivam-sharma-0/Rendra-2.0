package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ProjectDiagnostic;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure missing-module path correction. An existing unique module is evidence; a guessed module is not. */
final class RelativeImports {
    private static final Pattern MISSING = Pattern.compile("Cannot find module ['\"]([^'\"]+)['\"]");
    /** The bundler's report; tsc never reports a missing side-effect CSS import. */
    private static final Pattern UNRESOLVED = Pattern.compile("\\[UNRESOLVED_IMPORT\\] Could not resolve ['\"]([^'\"]+)['\"]");
    private static final Pattern SCRIPT = Pattern.compile("\\.(?:[cm]?[jt]sx?)$");
    private static final List<String> RESOLUTIONS = List.of("", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".css", ".json",
            "/index.ts", "/index.tsx", "/index.js", "/index.jsx");

    private RelativeImports() {}

    static boolean missingModule(ProjectDiagnostic diagnostic) {
        return module(diagnostic) != null;
    }

    private static String module(ProjectDiagnostic diagnostic) {
        if (diagnostic.message() == null) return null;
        var match = ("TS2307".equals(diagnostic.code()) ? MISSING : UNRESOLVED).matcher(diagnostic.message());
        return match.find() ? match.group(1) : null;
    }

    static String fix(String file, String source, Set<String> existing, List<ProjectDiagnostic> diagnostics) {
        String result = source;
        for (var diagnostic : diagnostics) {
            String module = module(diagnostic);
            if (module != null && file.equals(diagnostic.file())) result = rewrite(file, result, module, existing);
        }
        return result;
    }

    /** Every relative import that reaches no existing file, including CSS that only the bundler would report, so one
     * pass replaces a type-check and a build round per off-by-one import. Only {@code targets} (coordinator modules,
     * which exist before any unit runs) are candidates: an undiagnosed import may name a later wave's file. */
    static String fixAll(String file, String source, Set<String> existing, Set<String> targets) {
        String result = source;
        for (String module : ContractImports.importedModules(source))
            if (!resolves(file, module, existing)) result = rewrite(file, result, module, targets);
        return result;
    }

    private static boolean resolves(String file, String module, Set<String> existing) {
        if (!(module.startsWith("./") || module.startsWith("../"))) return true;
        Path parent = Path.of(file).getParent();
        String target = (parent == null ? Path.of("") : parent).resolve(module).normalize().toString().replace('\\', '/');
        // Node's .js specifiers name TS source.
        String stem = module.endsWith(".js") ? target.substring(0, target.length() - 3) : null;
        return RESOLUTIONS.stream().anyMatch(extension -> existing.contains(target + extension)
                || stem != null && existing.contains(stem + extension));
    }

    private static String rewrite(String file, String source, String module, Set<String> existing) {
        if (!(module.startsWith("./") || module.startsWith("../"))) return source;
        String leaf = Path.of(module).getFileName().toString();
        String name = SCRIPT.matcher(leaf).replaceFirst("");
        var candidates = existing.stream().filter(path -> samePackage(file, path))
                .filter(path -> {
                    String filename = Path.of(path).getFileName().toString();
                    return SCRIPT.matcher(filename).replaceFirst("").equals(name)
                            || SCRIPT.matcher(filename).replaceFirst("").equals("index")
                            && Path.of(path).getParent() != null && Path.of(path).getParent().getFileName().toString().equals(name);
                }).sorted().toList();
        if (candidates.size() != 1) return source;
        String target = candidates.getFirst();
        Path parent = Path.of(file).getParent();
        String relative = (parent == null ? Path.of("") : parent).relativize(Path.of(target)).toString().replace('\\', '/');
        // Node's .js specifiers resolve to TS source; extensionless imports remain extensionless.
        if (SCRIPT.matcher(target).find()) relative = SCRIPT.matcher(relative).replaceFirst(
                module.endsWith(".js") ? ".js" : module.endsWith(".mjs") ? ".mjs" : module.endsWith(".cjs") ? ".cjs" : "");
        if (!relative.startsWith(".")) relative = "./" + relative;
        return ContractImports.rewriteModule(source, module, relative);
    }

    private static boolean samePackage(String file, String target) {
        for (String root : List.of("client/", "server/")) if (file.startsWith(root)) return target.startsWith(root);
        return !target.startsWith("client/") && !target.startsWith("server/");
    }
}
