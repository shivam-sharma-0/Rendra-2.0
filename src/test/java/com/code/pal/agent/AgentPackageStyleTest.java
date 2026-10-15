package com.code.pal.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPackageStyleTest {
    private static final Path AGENT_SOURCES = Path.of("src/main/java/com/code/pal/agent");
    private static final int MAX_CODE_LINE = 200;
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])'");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile(
            "\\b(?:com\\.code\\.pal|java|javax|jakarta|org\\.springframework|tools\\.jackson)\\.(?:[a-z_]\\w*\\.)*[A-Z]\\w*");

    @Test
    void agentSourcesHaveNoOverlongCodeLinesOrQualifiedTypeNames() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(AGENT_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                inspect(file, violations);
            }
        }
        assertTrue(violations.isEmpty(), violations.size() + " style violations:\n"
                + String.join("\n", violations.subList(0, Math.min(80, violations.size()))));
    }

    private static void inspect(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        boolean inTextBlock = false;
        boolean inBlockComment = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int textBlockMarks = count(line, "\"\"\"");
            if (inTextBlock) {
                if (textBlockMarks % 2 == 1) {
                    inTextBlock = false;
                }
                continue;
            }
            String code = STRING_LITERAL.matcher(line.replace("\"\"\"", "")).replaceAll("\"\"");
            if (inBlockComment) {
                int end = code.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                inBlockComment = false;
                code = code.substring(end + 2);
            }
            int blockStart = code.indexOf("/*");
            if (blockStart >= 0 && code.indexOf("*/", blockStart) < 0) {
                inBlockComment = true;
                code = code.substring(0, blockStart);
            }
            code = code.replaceAll("/\\*.*?\\*/", "");
            int lineComment = code.indexOf("//");
            if (lineComment >= 0) {
                code = code.substring(0, lineComment);
            }
            if (textBlockMarks % 2 == 1) {
                inTextBlock = true;
            }
            String location = AGENT_SOURCES.relativize(file) + ":" + (index + 1);
            if (code.stripTrailing().length() > MAX_CODE_LINE) {
                violations.add(location + " line is " + code.stripTrailing().length() + " characters");
            }
            String trimmed = code.strip();
            if (!trimmed.startsWith("import ") && !trimmed.startsWith("package ")) {
                var qualified = QUALIFIED_TYPE.matcher(code);
                while (qualified.find()) {
                    violations.add(location + " fully qualified " + qualified.group());
                }
            }
        }
    }

    private static int count(String text, String token) {
        int found = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            found++;
        }
        return found;
    }
}
