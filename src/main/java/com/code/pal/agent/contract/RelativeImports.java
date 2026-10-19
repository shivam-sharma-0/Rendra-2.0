package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ProjectDiagnostic;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure TS2307 path correction. An existing unique module is evidence; a guessed module is not. */
final class RelativeImports {
    private static final Pattern MISSING = Pattern.compile("Cannot find module ['\"]([^'\"]+)['\"]");
    private static final Pattern SCRIPT = Pattern.compile("\\.(?:[cm]?[jt]sx?)$");

    private RelativeImports() {}

    static String fix(String file, String source, Set<String> existing, List<ProjectDiagnostic> diagnostics) {
        String result = source;
        for (var diagnostic : diagnostics) {
            if (!"TS2307".equals(diagnostic.code()) || !file.equals(diagnostic.file()) || diagnostic.message() == null) continue;
            var match = MISSING.matcher(diagnostic.message());
            if (!match.find()) continue;
            String module = match.group(1);
            if (!(module.startsWith("./") || module.startsWith("../"))) continue;
            String leaf = Path.of(module).getFileName().toString();
            String name = SCRIPT.matcher(leaf).replaceFirst("");
            var candidates = existing.stream().filter(path -> samePackage(file, path))
                    .filter(path -> {
                        String filename = Path.of(path).getFileName().toString();
                        return SCRIPT.matcher(filename).replaceFirst("").equals(name)
                                || SCRIPT.matcher(filename).replaceFirst("").equals("index")
                                && Path.of(path).getParent() != null && Path.of(path).getParent().getFileName().toString().equals(name);
                    }).sorted().toList();
            if (candidates.size() != 1) continue;
            String target = candidates.getFirst();
            Path parent = Path.of(file).getParent();
            String relative = (parent == null ? Path.of("") : parent).relativize(Path.of(target)).toString().replace('\\', '/');
            // Node's .js specifiers resolve to TS source; extensionless imports remain extensionless.
            if (SCRIPT.matcher(target).find()) relative = SCRIPT.matcher(relative).replaceFirst(
                    module.endsWith(".js") ? ".js" : module.endsWith(".mjs") ? ".mjs" : module.endsWith(".cjs") ? ".cjs" : "");
            if (!relative.startsWith(".")) relative = "./" + relative;
            result = ContractImports.rewriteModule(result, module, relative);
        }
        return result;
    }

    private static boolean samePackage(String file, String target) {
        for (String root : List.of("client/", "server/")) if (file.startsWith(root)) return target.startsWith(root);
        return !target.startsWith("client/") && !target.startsWith("server/");
    }
}
