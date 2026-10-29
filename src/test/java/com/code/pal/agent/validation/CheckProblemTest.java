package com.code.pal.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CheckProblemTest {

    private static ValidationReport report(List<ProjectDiagnostic> introduced, ValidationCheck... checks) {
        return new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "npm", "build",
                introduced, List.of(), 2, 0, 3, "fixture", List.of(checks));
    }

    private static ProjectDiagnostic diagnostic(String code, String message) {
        return new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, message, "browser", code);
    }

    @Test
    void aFailedBrowserStepIsNamedAndPageSetupAdviceNeverHidesIt() {
        var problem = CheckProblem.of(report(List.of(
                        diagnostic("BASELINE_DESCRIPTION", "Add <meta name=\"description\">"),
                        diagnostic("BROWSER_ACCEPTANCE_FAILED", "Required interaction ui_complete did not pass: CLICK  [data-task]\ninput")),
                new ValidationCheck("TYPE_CHECK", ValidationCheck.Outcome.PASS, true, ""),
                new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true, "")));
        assertEquals("Problem found: the “complete” step did not work in the browser", problem.title());
        assertEquals("Required interaction ui_complete did not pass: CLICK [data-task] input", problem.detail());
    }

    @Test
    void otherChecksUsePlainWordsAndLongDetailsAreClipped() {
        assertEquals("Problem found: the “wrong password” API check failed", CheckProblem.of(report(List.of(),
                new ValidationCheck("API_platform_wrong_password", ValidationCheck.Outcome.FAIL, true, ""))).title());
        var types = CheckProblem.of(report(List.of(diagnostic("TS2304", "x".repeat(300))),
                new ValidationCheck("TYPE_CHECK", ValidationCheck.Outcome.FAIL, true, "")));
        assertEquals("Problem found: the code has type errors", types.title());
        assertEquals(201, types.detail().length());
        // 2026-10-29 E2E 38: a wave type check has no check list and read "a required check did not pass".
        var wave = new ValidationReport(ValidationOperation.TYPE_CHECK, true, false, false, false, 1, "npm", "tsc",
                List.of(diagnostic("TS2345", "Argument of type 'Todo' is not assignable")), List.of(), 1, 1, 2, "fixture", List.of());
        assertEquals("Problem found: the code has type errors", CheckProblem.of(wave).title());
        var silent = CheckProblem.of(report(List.of(), new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true, "")));
        assertEquals("Problem found: the app did not work as expected in a browser", silent.title());
        assertNull(silent.detail());
    }
}
