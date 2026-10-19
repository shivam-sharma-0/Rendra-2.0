package com.code.pal.agent.repair;

import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationReport;

import java.util.LinkedHashSet;

/** Keeps blocking check evidence ahead of advisory diagnostics in bounded model repair feedback. */
public final class RepairFeedback {
    private RepairFeedback() {}

    public static String prompt(ValidationReport report) {
        var details = new LinkedHashSet<String>();
        report.checks().stream().filter(ValidationCheck::blocksSuccess).limit(8)
                .forEach(check -> details.add("- " + check.id() + " [" + check.outcome() + "] - " + check.reason()));
        report.introducedDiagnostics().stream()
                .filter(item -> item.severity() == DiagnosticSeverity.ERROR).limit(8)
                .map(RepairFeedback::describe).forEach(details::add);
        report.introducedDiagnostics().stream()
                .filter(item -> item.severity() == DiagnosticSeverity.WARNING && "UI_ACCESSIBILITY".equals(item.code()))
                .limit(3).map(RepairFeedback::describe).forEach(details::add);
        return PromptLoader.load(Prompt.FINAL_VALIDATION_REPAIR)
                .replace("{{diagnostics}}", String.join("\n", details));
    }

    private static String describe(ProjectDiagnostic item) {
        String location = item.file() == null ? "project" : item.file()
                + (item.line() == null ? "" : ":" + item.line()
                + (item.column() == null ? "" : ":" + item.column()));
        return "- " + location + " [" + item.severity() + "] - " + item.message();
    }
}
