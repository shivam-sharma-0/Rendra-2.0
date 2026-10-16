package com.code.pal.agent;

import com.code.pal.agent.domain.AgentFailureReason;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationReport;

import java.util.Map;

/**
 * What a failed build tells the user: what went wrong in plain words, that nothing was saved, and what to do next.
 * Built from recorded outcomes only (no model call), so it never claims more than the checks showed. File paths
 * stay in Details; the chat shows the kind of problem and the first error.
 */
final class FailureExplanation {
    static final String NOTHING_SAVED = "Nothing was saved; your previous version is unchanged.";
    static final String RETRY = "Retry to let me continue from this draft, or ask for a smaller first step.";
    private static final int MAX_DETAIL = 200;
    private static final Map<String, String> CHECKS = Map.of(
            "STRUCTURE", "the project files were incomplete",
            "TYPE_CHECK", "the code still had type errors after my repair attempts",
            "BUILD", "the app did not build after my repair attempts",
            "RUNTIME", "the app built, but it did not behave as approved when I tried it in a browser");

    private FailureExplanation() {
    }

    static String validation(ValidationReport report) {
        if (report == null) {
            return "I couldn't finish this build. " + NOTHING_SAVED + " " + RETRY;
        }
        if (report.outcome() == ValidationCheck.Outcome.SKIPPED) {
            return "A required check couldn't run, so I couldn't confirm the build works. " + NOTHING_SAVED
                    + " Try again in a minute.";
        }
        String cause = report.checks().stream().filter(ValidationCheck::blocksSuccess).findFirst()
                .map(check -> CHECKS.getOrDefault(check.id(), "a required check (" + check.id() + ") did not pass"))
                .orElse("the checks still failed after my repair attempts");
        String first = report.introducedDiagnostics().stream().findFirst().map(FailureExplanation::detail).orElse("");
        return "I couldn't finish this build: " + cause + "." + first + "\n\n" + NOTHING_SAVED + " " + RETRY;
    }

    static String failure(AgentFailureReason reason, boolean codeSaved, String reviewMessage) {
        if (codeSaved) {
            return "Your code changes were saved. Preview preparation failed; the saved code is available.";
        }
        return switch (reason) {
            case USER_REVIEW_REQUIRED -> reviewMessage;
            case RUN_LIMIT_REACHED -> "I ran out of steps before finishing this build. " + NOTHING_SAVED
                    + " Retry to continue, or split the request into smaller parts.";
            case PROVIDER_REQUEST_REJECTED -> "The AI model rejected this request. " + NOTHING_SAVED
                    + " Try again; a shorter request or fewer attachments usually helps.";
            default -> "Something went wrong on our side before this build finished. " + NOTHING_SAVED + " Please try again.";
        };
    }

    private static String detail(ProjectDiagnostic diagnostic) {
        String message = diagnostic.message() == null ? "" : diagnostic.message().replaceAll("\\s+", " ").strip();
        if (message.isEmpty()) {
            return "";
        }
        return " First problem: " + (message.length() > MAX_DETAIL ? message.substring(0, MAX_DETAIL) + "…" : message);
    }
}
