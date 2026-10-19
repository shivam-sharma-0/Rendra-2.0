package com.code.pal.agent.repair;

import com.code.pal.agent.validation.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RepairFeedbackTest {
    @Test
    void requiredFeatureFailureSurvivesAdvisoryNoiseAndAbsentFileDiagnostics() {
        var warnings = IntStream.range(0, 24).mapToObj(index -> new ProjectDiagnostic(null, null, null,
                DiagnosticSeverity.WARNING, "Advisory warning " + index, "model-spec", "MODEL_SPEC")).toList();
        var report = new ValidationReport(ValidationOperation.ALL, true, true, false, false, 0, "npm", "build",
                warnings, List.of(), 1, 2, 4, "Failed", List.of(
                new ValidationCheck("RUNTIME", ValidationCheck.Outcome.PASS, true, "Page loaded"),
                new ValidationCheck("APPROVED_BEHAVIOUR", ValidationCheck.Outcome.FAIL, false, "Advisory failure"),
                new ValidationCheck("FEATURE_tasks", ValidationCheck.Outcome.FAIL, true,
                        "phase=prepare; expected editable name=title")));

        String feedback = RepairFeedback.prompt(report);

        assertTrue(feedback.contains("FEATURE_tasks [FAIL]"));
        assertTrue(feedback.contains("name=title"));
        assertFalse(feedback.contains("Advisory warning"));
        assertFalse(feedback.contains("Advisory failure"));
        assertTrue(feedback.contains("Update existing declarations"));
    }

    @Test
    void compilerErrorKeepsItsExactLocationAndMessage() {
        var report = new ValidationReport(ValidationOperation.TYPE_CHECK, true, false, false, false, 1, "npm", "tsc",
                List.of(new ProjectDiagnostic("src/App.tsx", 12, 9, DiagnosticSeverity.ERROR,
                        "Cannot redeclare block-scoped variable formData", "typescript", "TS2451")),
                List.of(), 1, 2, 5, "Failed");

        String feedback = RepairFeedback.prompt(report);

        assertTrue(feedback.contains("src/App.tsx:12:9"));
        assertTrue(feedback.contains("Cannot redeclare block-scoped variable formData"));
    }

    @Test
    void accessibilityFeedbackStaysVisibleAfterTheBlockingFeatureFailure() {
        var report = new ValidationReport(ValidationOperation.ALL, true, true, false, false, 0, "npm", "build",
                List.of(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.WARNING,
                        "A completion checkbox needs a label", "visual", "UI_ACCESSIBILITY")),
                List.of(), 1, 2, 4, "Failed", List.of(
                new ValidationCheck("FEATURE_tasks", ValidationCheck.Outcome.FAIL, true, "Missing editable field")));
        String feedback = RepairFeedback.prompt(report);
        assertTrue(feedback.indexOf("FEATURE_tasks") < feedback.indexOf("[WARNING]"));
        assertTrue(feedback.contains("A completion checkbox needs a label"));
    }
}
