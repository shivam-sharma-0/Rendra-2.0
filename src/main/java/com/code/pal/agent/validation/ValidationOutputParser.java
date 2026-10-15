package com.code.pal.agent.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns validation command output (tsc, vite, test runners) into diagnostics the model can act on:
 * project-relative file, line, column and a message. Stack frames and anything inside installed
 * dependencies are noise and are dropped; a location printed on its own line takes its message from the
 * preceding line (Vite prints {@code [plugin] what went wrong} and then {@code file: path:line:col}).
 */
final class ValidationOutputParser {
    private static final Pattern TS_PAREN = Pattern.compile("^(.+?)\\((\\d+),(\\d+)\\):\\s*(error|warning)\\s*([A-Za-z]+\\d+)?:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON = Pattern.compile("^(.+?):(\\d+):(\\d+)\\s*(?:[-:]\\s*)?(error|warning)?\\s*([A-Za-z]+\\d+)?:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    /** A JavaScript stack frame: {@code at fn (file:line:col)} or {@code at file:line:col}. */
    private static final Pattern FRAME = Pattern.compile("^at\\s.*");
    /** {@code at fn (location)}: the location is inside the parentheses. */
    private static final Pattern FRAME_CALL = Pattern.compile("^at\\s.*?\\((.+)\\)$");
    private static final Pattern LOCATION_PREFIX = Pattern.compile("^(?:file:\\s*|at\\s+)", Pattern.CASE_INSENSITIVE);
    /** Rolldown/Oxc (miette) labels: {@code ╭─[ src/App.tsx:2:10 ]}, also {@code ┌─[ ... ]} and ASCII {@code ,-[ ... ]}. */
    private static final Pattern BOX_LOCATION = Pattern.compile("^[\\x{256D}\\x{250C},][\\x{2500}-]+\\[\\s*(.+?)\\s*\\]$");
    private static final int MAX_MESSAGE = 400;

    private ValidationOutputParser() {
    }

    /** @param root the directory the command ran in, as the command saw it */
    static List<ProjectDiagnostic> parse(String output, String root, ValidationOperation operation) {
        String source = operation.name().toLowerCase(Locale.ROOT);
        List<ProjectDiagnostic> diagnostics = new ArrayList<>();
        String context = null;   // the last line that described a problem
        for (String raw : output.split("\\R")) {
            String line = ANSI.matcher(raw).replaceAll("").trim();
            if (line.isEmpty()) continue;
            boolean frame = FRAME.matcher(line).matches();
            Matcher call = frame ? FRAME_CALL.matcher(line) : null;
            String location = call != null && call.matches() ? call.group(1) : LOCATION_PREFIX.matcher(line).replaceFirst("");
            Matcher box = BOX_LOCATION.matcher(location);
            if (box.matches()) location = box.group(1);
            Matcher matcher = TS_PAREN.matcher(location);
            if (!matcher.matches()) matcher = COLON.matcher(location);
            if (matcher.matches()) {
                String file = normalizeFile(root, matcher.group(1));
                if (dependencyInternal(file) || (frame && file.contains("://"))) continue;
                String message = matcher.group(6) == null ? "" : matcher.group(6).trim();
                if (message.isEmpty() || message.equals(")") || message.equals("]")) message = context == null ? "" : context;
                if (frame && message.isEmpty()) continue;
                diagnostics.add(new ProjectDiagnostic(file, Integer.valueOf(matcher.group(2)), Integer.valueOf(matcher.group(3)),
                        "warning".equalsIgnoreCase(matcher.group(4)) ? DiagnosticSeverity.WARNING : DiagnosticSeverity.ERROR,
                        truncate(normalizeMessage(root, message)), source, matcher.group(5)));
                continue;
            }
            if (!frame && meaningful(line)) context = line;
        }
        return diagnostics;
    }

    /** A message for a failed command whose output had no recognizable location. */
    static String summary(String output, String root) {
        List<String> lines = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = ANSI.matcher(raw).replaceAll("").trim();
            if (line.isEmpty() || FRAME.matcher(line).matches() || !meaningful(line)) continue;
            lines.add(line);
            if (lines.size() == 3) break;
        }
        return lines.isEmpty() ? "Validation command failed" : truncate(normalizeMessage(root, String.join(" | ", lines)));
    }

    /** A tool's file reference relative to the directory the command ran in. */
    static String normalizeFile(String root, String raw) {
        String clean = raw.trim().replace('\\', '/').replaceFirst("^file://", "");
        String base = root.replace('\\', '/').replaceAll("/+$", "");
        if (!base.isEmpty() && clean.regionMatches(true, 0, base + "/", 0, base.length() + 1)) {
            clean = clean.substring(base.length() + 1);
        }
        while (clean.startsWith("./")) clean = clean.substring(2);
        return clean;
    }

    static String normalizeMessage(String root, String message) {
        String forward = root.replace('\\', '/');
        return message.replace(root, "<project>").replace(forward, "<project>");
    }

    private static boolean dependencyInternal(String file) {
        return file.contains("node_modules/") || file.contains(".codepal/deps/") || file.startsWith("node:")
                || file.startsWith("internal/");
    }

    /** Lines that say something: not bare banners such as {@code error during build:}. */
    private static boolean meaningful(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return !lower.equals("error during build:") && !lower.startsWith("npm error") && line.length() > 3
                && !line.matches("^[>\\s].*") && !lower.startsWith("node.js v");
    }

    private static String truncate(String value) {
        return value.length() <= MAX_MESSAGE ? value : value.substring(0, MAX_MESSAGE) + "...";
    }
}
