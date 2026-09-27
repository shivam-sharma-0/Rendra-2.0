package com.code.pal.agent.validation;

import java.util.List;

public record ValidationReport(
        ValidationOperation operation,
        boolean available,
        boolean commandSucceeded,
        boolean changeValidationPassed,
        boolean timedOut,
        Integer exitCode,
        String packageManager,
        String script,
        List<ProjectDiagnostic> introducedDiagnostics,
        List<ProjectDiagnostic> preExistingDiagnostics,
        int repairAttempts,
        int repairAttemptsRemaining,
        int changeSetRevision,
        String summary) {
}
