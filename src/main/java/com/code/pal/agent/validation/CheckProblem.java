package com.code.pal.agent.validation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A failing report as the owner reads it: what broke, in plain words, and the first diagnostic as a detail line.
 *
 * @param title  "Problem found: …" naming the first blocking check
 * @param detail the first introduced diagnostic (page-setup advice only when nothing else failed), or {@code null}
 */
public record CheckProblem(String title, String detail) {
    private static final Pattern INTERACTION = Pattern.compile("Required interaction (\\S+) did not pass");
    private static final int MAX_DETAIL = 200;

    public static CheckProblem of(ValidationReport report) {
        Optional<ProjectDiagnostic> first = report.introducedDiagnostics().stream()
                .filter(diagnostic -> diagnostic.code() == null || !diagnostic.code().startsWith("BASELINE_")).findFirst()
                .or(() -> report.introducedDiagnostics().stream().findFirst());
        String message = first.map(ProjectDiagnostic::message).map(text -> text.replaceAll("\\s+", " ").strip()).orElse("");
        String check = report.checks().stream().filter(ValidationCheck::blocksSuccess).map(ValidationCheck::id)
                .findFirst().orElse("");
        return new CheckProblem("Problem found: " + cause(check, message), message.isEmpty() ? null
                : message.length() <= MAX_DETAIL ? message : message.substring(0, MAX_DETAIL) + "…");
    }

    private static String cause(String check, String message) {
        if (check.startsWith("API_"))
            return "the " + named(check.substring(4)) + " API check failed";
        return switch (check) {
            case "STRUCTURE" -> "some project files are incomplete";
            case "TYPE_CHECK" -> "the code has type errors";
            case "BUILD" -> "the app does not build";
            case "RUNTIME" -> {
                var interaction = INTERACTION.matcher(message);
                yield interaction.find() ? "the " + named(interaction.group(1)) + " step did not work in the browser"
                        : "the app did not work as expected in a browser";
            }
            case "BASELINE" -> "basic page setup is missing";
            default -> "a required check did not pass";
        };
    }

    /** {@code ui_complete} → “complete”, {@code platform_wrong_password} → “wrong password”. */
    private static String named(String id) {
        return "“" + id.replaceFirst("^(ui|api|platform)_", "").replace('_', ' ') + "”";
    }
}
