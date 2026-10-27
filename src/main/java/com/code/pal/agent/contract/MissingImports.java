package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ProjectDiagnostic;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Adds the import a generated file forgot for a React hook it calls or a contract type tsc cannot find. Never guesses:
 * hooks come only from 'react', types only from the coordinator types module, and a declared or imported name is left. */
final class MissingImports {
    private static final Pattern HOOK = Pattern.compile(
            "(?<![.\\w$])(use(?:State|Effect|Memo|Callback|Ref|Context|Reducer|LayoutEffect|Id|Transition))\\s*[<(]");
    private static final Pattern NOT_FOUND = Pattern.compile("Cannot find name '([A-Za-z_$][\\w$]*)'");

    private MissingImports() {}

    static String missingName(ProjectDiagnostic diagnostic) {
        if (!Set.of("TS2304", "TS2552").contains(diagnostic.code()) || diagnostic.message() == null) return null;
        var match = NOT_FOUND.matcher(diagnostic.message());
        return match.find() ? match.group(1) : null;
    }

    static String hooks(String file, String source) {
        if (!file.matches("client/.+\\.tsx?")) return source;
        var missing = new TreeSet<String>();
        for (var match = HOOK.matcher(source); match.find();)
            if (!declared(source, match.group(1))) missing.add(match.group(1));
        return missing.isEmpty() ? source : "import { " + String.join(", ", missing) + " } from 'react'\n" + source;
    }

    /** {@code typesModule} is the coordinator types.ts of the file's package; {@code names} the contract's type names. */
    static String types(String file, String source, List<ProjectDiagnostic> diagnostics, Set<String> names, String typesModule) {
        var missing = new TreeSet<String>();
        for (var diagnostic : diagnostics) {
            String name = missingName(diagnostic);
            if (name != null && file.equals(diagnostic.file()) && names.contains(name) && !declared(source, name)) missing.add(name);
        }
        if (missing.isEmpty()) return source;
        Path parent = Path.of(file).getParent();
        String relative = (parent == null ? Path.of("") : parent).relativize(Path.of(typesModule)).toString().replace('\\', '/')
                .replaceFirst("\\.ts$", file.startsWith("server/") ? ".js" : "");
        if (!relative.startsWith(".")) relative = "./" + relative;
        return "import type { " + String.join(", ", missing) + " } from '" + relative + "'\n" + source;
    }

    private static boolean declared(String source, String name) {
        String word = "\\b" + Pattern.quote(name) + "\\b";
        return Pattern.compile("\\bimport\\b[^'\";]*" + word + "[^'\";]*\\bfrom\\s*['\"]").matcher(source).find()
                || Pattern.compile("\\b(?:function|const|let|var|class|interface|type|enum)\\s+" + word).matcher(source).find();
    }
}
