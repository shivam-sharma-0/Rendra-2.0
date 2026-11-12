package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ProjectDiagnostic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExpressParams {
    private static final Pattern RAW = Pattern.compile("(?<!String\\()\\breq\\.(params|query)\\.([A-Za-z_$][\\w$]*)\\b");

    private ExpressParams() {
    }

    static boolean diagnosed(ProjectDiagnostic diagnostic) {
        String message = diagnostic.message();
        return message != null && message.contains("'string | string[]'") && diagnostic.file() != null && diagnostic.line() != null;
    }

    static String fix(String path, String source, List<ProjectDiagnostic> diagnostics) {
        if (diagnostics == null || !path.matches("server/.+\\.ts"))
            return source;
        var lines = source.split("\n", -1);
        boolean changed = false;
        for (var diagnostic : diagnostics) {
            if (!diagnosed(diagnostic) || !path.equals(diagnostic.file()) || diagnostic.line() < 1 || diagnostic.line() > lines.length)
                continue;
            int index = diagnostic.line() - 1;
            String fixed = RAW.matcher(lines[index]).replaceAll(match -> Matcher.quoteReplacement(
                    "String(req." + match.group(1) + "." + match.group(2) + " ?? '')"));
            if (!fixed.equals(lines[index])) {
                lines[index] = fixed;
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : source;
    }
}
