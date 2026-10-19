package com.code.pal.agent;

import com.code.pal.agent.domain.AgentFailureReason;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureExplanationTest {
    private static ValidationReport report(boolean available, List<ProjectDiagnostic> introduced, ValidationCheck... checks) {
        return new ValidationReport(ValidationOperation.BUILD, available, false, false, false, 1, "npm", "build",
                introduced, List.of(), 2, 0, 3, "fixture", List.of(checks));
    }

    @Test
    void aFailedBrowserCheckSaysTheAppMisbehavedAndNamesTheFirstProblemWithoutFilePaths() {
        var diagnostic = new ProjectDiagnostic("src/App.tsx", 12, 3, DiagnosticSeverity.ERROR,
                "Missing   task row\nafter adding", "browser", "BROWSER_ACCEPTANCE_FAILED");
        String text = FailureExplanation.validation(report(true, List.of(diagnostic),
                new ValidationCheck("TYPE_CHECK", ValidationCheck.Outcome.PASS, true, ""),
                new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true, "")));

        assertTrue(text.contains("did not behave as approved"), text);
        assertTrue(text.contains("First problem: Missing task row after adding"), text);
        assertFalse(text.contains("src/App.tsx"), "paths stay in Details");
        assertTrue(text.contains(FailureExplanation.NOTHING_SAVED) && text.contains(FailureExplanation.RETRY));
    }

    @Test
    void anUnavailableCheckIsNotBlamedOnTheCode() {
        String text = FailureExplanation.validation(report(false, List.of(),
                new ValidationCheck("BUILD", ValidationCheck.Outcome.SKIPPED, true, "sandbox down")));
        assertTrue(text.contains("couldn't run"), text);
        assertFalse(text.contains("repair attempts"), text);
    }

    @Test
    void longErrorsAreShortenedAndUnknownChecksAreNamed() {
        var diagnostic = new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, "x".repeat(500), "tsc", null);
        String text = FailureExplanation.validation(report(true, List.of(diagnostic),
                new ValidationCheck("API_health", ValidationCheck.Outcome.FAIL, true, "")));
        assertTrue(text.contains("(API_health)"), text);
        assertTrue(text.contains("x".repeat(200) + "…") && !text.contains("x".repeat(201)), "bounded detail");
    }

    @Test
    void aSavedBuildSaysWhenRequestedBehaviourCouldNotBeConfirmed() {
        var advisory = new ValidationCheck(FailureExplanation.APPROVED_BEHAVIOUR, ValidationCheck.Outcome.FAIL, false,
                "add_task_scenario_0, complete_task_scenario_0");
        String note = FailureExplanation.unconfirmedBehaviour(report(true, List.of(), advisory));
        assertTrue(note.contains("Build saved; some requested behaviour could not be confirmed"), note);
        assertTrue(note.contains("add_task_scenario_0"), note);

        var passed = new ValidationCheck(FailureExplanation.APPROVED_BEHAVIOUR, ValidationCheck.Outcome.PASS, false, "");
        assertEquals("", FailureExplanation.unconfirmedBehaviour(report(true, List.of(), passed)));
        assertEquals("", FailureExplanation.unconfirmedBehaviour(null));
    }

    @Test
    void otherFailuresSayWhatToDoAndNeverClaimASave() {
        assertTrue(FailureExplanation.failure(AgentFailureReason.RUN_LIMIT_REACHED, false, "x").contains("ran out of steps"));
        assertTrue(FailureExplanation.failure(AgentFailureReason.PROVIDER_REQUEST_REJECTED, false, "x").contains("rejected"));
        assertEquals("Approve the requirements first",
                FailureExplanation.failure(AgentFailureReason.USER_REVIEW_REQUIRED, false, "Approve the requirements first"));
        assertTrue(FailureExplanation.failure(AgentFailureReason.AGENT_FAILED, false, "x").contains(FailureExplanation.NOTHING_SAVED));
        assertTrue(FailureExplanation.failure(AgentFailureReason.AGENT_FAILED, true, "x").startsWith("Your code changes were saved"));
    }

    @Test
    void exhaustedRepairsUseTheLastFailedValidationInsteadOfAnOpaqueRepairLimit() {
        var diagnostic = new ProjectDiagnostic("client/src/App.tsx", 4, 2, DiagnosticSeverity.ERROR,
                "Task row did not appear", "browser", "BROWSER_ACCEPTANCE_FAILED");
        String text = FailureExplanation.failure(AgentFailureReason.USER_REVIEW_REQUIRED, false, "Repair limit reached",
                report(true, List.of(diagnostic),
                        new ValidationCheck("TYPE_CHECK", ValidationCheck.Outcome.PASS, true, ""),
                        new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true, "")));

        assertTrue(text.contains("did not behave as approved"), text);
        assertTrue(text.contains("Task row did not appear"), text);
        assertFalse(text.contains("client/src/App.tsx"), "paths stay in Details");
    }

    @Test
    void unsupportedIndependentFeatureCoverageIsVisibleEvenWhenAdvisoryScenariosPassed() {
        String note = FailureExplanation.unconfirmedBehaviour(report(true, List.of(),
                new ValidationCheck("APPROVED_BEHAVIOUR", ValidationCheck.Outcome.PASS, false, "Model scenarios passed"),
                new ValidationCheck("FEATURE_COVERAGE", ValidationCheck.Outcome.SKIPPED, false, "custom_workflow")));
        assertTrue(note.contains("do not yet have independent outcome coverage"));
        assertFalse(note.contains("custom_workflow"), "Feature ids stay in Details");
    }
}
