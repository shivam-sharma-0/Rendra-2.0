package com.code.pal.agent.contract;

import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationReport;

import java.util.LinkedHashSet;
import java.util.Set;

/** Trusted check IDs or canonical runtime diagnostics may select declared acceptance owners; arbitrary text may not. */
public final class ContractAcceptanceFailures {
    private ContractAcceptanceFailures() {
    }

    public static Set<String> ids(BuildContract contract, ValidationReport report) {
        var known = new LinkedHashSet<String>();
        contract.acceptance().browser().forEach(action -> known.add(action.id()));
        contract.acceptance().api().forEach(action -> known.add(action.id()));
        var failed = new LinkedHashSet<String>();
        for (var check : report.checks()) {
            if (check.required() && check.outcome() == ValidationCheck.Outcome.FAIL
                    && (check.id().startsWith("BROWSER_") || check.id().startsWith("API_"))) {
                String id = check.id().replaceFirst("^(BROWSER_|API_)", "");
                if (known.contains(id)) {
                    failed.add(id);
                }
            }
        }
        for (var diagnostic : report.introducedDiagnostics()) {
            if (diagnostic.severity() == DiagnosticSeverity.ERROR && "BROWSER_ACCEPTANCE_FAILED".equals(diagnostic.code())) {
                contract.acceptance().browser().stream().filter(action -> action.failureMessage().equals(diagnostic.message())
                        || diagnostic.message() != null && diagnostic.message().startsWith(action.failureMessage() + " (the page was at ")
                        || ("Required interaction " + action.id() + " did not pass").equals(diagnostic.message()))
                        .forEach(action -> failed.add(action.id()));
            }
        }
        return failed;
    }
}
