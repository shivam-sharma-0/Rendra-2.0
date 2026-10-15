package com.code.pal.agent.contract;

import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationCheck;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable evidence; the caller cannot turn missing or skipped coverage into certification. */
public record ValidationBundle(ValidationRequest requestIdentity, String diagnosticsRevision,
        List<ValidationCheck> checks, List<ProjectDiagnostic> diagnostics, List<CommandReceipt> commands,
        String artifactHash, boolean certifyEligible) {
    public record CommandReceipt(String id, String inputHash, ValidationCheck.Outcome status, String artifactHash) {
        public CommandReceipt {
            if (!List.of("TYPE_CHECK", "BUILD").contains(id) || inputHash == null || inputHash.isBlank()) {
                throw new IllegalArgumentException("Invalid command receipt");
            }
            Objects.requireNonNull(status);
        }
    }

    public ValidationBundle {
        Objects.requireNonNull(requestIdentity);
        Objects.requireNonNull(diagnosticsRevision);
        checks = List.copyOf(checks);
        diagnostics = List.copyOf(diagnostics);
        commands = List.copyOf(commands);
        var ids = new HashSet<String>();
        boolean unique = checks.stream().allMatch(check -> ids.add(check.id()));
        boolean coverage = unique && ids.containsAll(requestIdentity.requiredChecks())
                && checks.stream().noneMatch(check -> check.blocksSuccess()
                        || requestIdentity.requiredChecks().contains(check.id()) && check.outcome() != ValidationCheck.Outcome.PASS);
        var commandIds = new HashSet<String>();
        boolean compiler = commands.stream().allMatch(command -> commandIds.add(command.id()) && command.status() == ValidationCheck.Outcome.PASS);
        compiler &= commandIds.contains("BUILD") && commands.stream().anyMatch(command -> command.id().equals("BUILD")
                && Objects.equals(command.artifactHash(), artifactHash))
                && (!requestIdentity.requiredChecks().contains("TYPE_CHECK") || commandIds.contains("TYPE_CHECK"));
        certifyEligible = certifyEligible && requestIdentity.phase() == ValidationRequest.Phase.FINAL
                && artifactHash != null && !artifactHash.isBlank() && coverage && compiler
                && diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }
}
