package com.code.pal.project.brief;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code RENDRA.md}: the project brief Rendra keeps at the project root — what the project is, its
 * approved requirements and the lasting decisions made since. It is an ordinary project file, so it is
 * versioned, committed and restored with the code; the agent reads it before every change (as a project
 * instruction file). The backend replaces its managed approval/snapshot blocks; user notes outside
 * those blocks remain editable. Approved decisions come from durable revisions, not arbitrary file text.
 * Named brief paths are blocked by every public static/preview/published route.
 */
public final class ProjectBriefFile {

    public static final String NAME = "RENDRA.md";
    /** Includes nested/case/Windows trailing-dot aliases and backup copies of the private brief. */
    public static boolean privatePath(String path) {
        if (path == null) return false;
        for (String part : path.replace('\\', '/').split("/")) {
            String name = part.toLowerCase(java.util.Locale.ROOT).stripTrailing();
            if (name.equals("rendra.md") || name.startsWith("rendra.md.") || name.startsWith("rendra.md;")) return true;
        }
        return false;
    }

    private static final int MAX_SUMMARY = 600;
    private static final int MAX_ANSWER = 400;

    private ProjectBriefFile() {
    }

    /** One approved requirement: the question and the answer chosen or written. */
    public record Requirement(String question, String answer) {}

    /** The first brief of a project, written when its requirements are approved. */
    public static String initial(String projectName, String initialPrompt, List<Requirement> requirements, LocalDate approvedOn) {
        StringBuilder text = new StringBuilder();
        text.append("# ").append(blankOr(projectName, "Project")).append("\n\n");
        text.append("This is the project brief Rendra keeps for this project. Rendra reads it before every change and ")
                .append("updates it when a lasting decision is made. You can edit it too.\n\n");
        text.append("## What it is\n\n").append(clip(blankOr(initialPrompt, "(not described yet)"), MAX_SUMMARY)).append("\n\n");
        text.append("## Requirements (approved ").append(approvedOn).append(")\n\n");
        if (requirements.isEmpty()) text.append("- (none recorded)\n");
        for (Requirement requirement : requirements) {
            text.append("- ").append(oneLine(requirement.question())).append(": ")
                    .append(clip(oneLine(blankOr(requirement.answer(), "Not specified")), MAX_ANSWER)).append('\n');
        }
        text.append("\n## Decisions\n\n- (none yet)\n");
        return text.toString();
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).strip() + "…";
    }
}
