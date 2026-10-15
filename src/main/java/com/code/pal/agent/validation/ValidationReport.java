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
        String summary,
        List<ValidationCheck> checks) {
    public ValidationReport {
        checks=checks==null?List.of():List.copyOf(checks);
        if(checks.stream().anyMatch(ValidationCheck::blocksSuccess))changeValidationPassed=false;
    }
    /** Compatible constructor for historical focused tools/fixtures; availability still exposes skips. */
    public ValidationReport(ValidationOperation operation,boolean available,boolean commandSucceeded,boolean changeValidationPassed,
            boolean timedOut,Integer exitCode,String packageManager,String script,List<ProjectDiagnostic> introducedDiagnostics,
            List<ProjectDiagnostic> preExistingDiagnostics,int repairAttempts,int repairAttemptsRemaining,int changeSetRevision,String summary) {
        this(operation,available,commandSucceeded,changeValidationPassed,timedOut,exitCode,packageManager,script,introducedDiagnostics,
                preExistingDiagnostics,repairAttempts,repairAttemptsRemaining,changeSetRevision,summary,List.of());
    }
    public ValidationReport withChecks(List<ValidationCheck> checks) {
        return new ValidationReport(operation,available,commandSucceeded,changeValidationPassed,timedOut,exitCode,packageManager,script,
                introducedDiagnostics,preExistingDiagnostics,repairAttempts,repairAttemptsRemaining,changeSetRevision,summary,checks);
    }
    @com.fasterxml.jackson.annotation.JsonProperty("outcome")
    public ValidationCheck.Outcome outcome() {
        if(!available || checks.stream().anyMatch(ValidationCheck::unavailable))return ValidationCheck.Outcome.SKIPPED;
        return changeValidationPassed?ValidationCheck.Outcome.PASS:ValidationCheck.Outcome.FAIL;
    }
}
