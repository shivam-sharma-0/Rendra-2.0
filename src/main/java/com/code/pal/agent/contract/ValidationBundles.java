package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationReport;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** S6 orchestration; the S7 adapter owns actual commands/artifacts/acceptance, never model text. */
public final class ValidationBundles {
    public interface Receipts {
        ValidationBundle.CommandReceipt find(ValidationRequest request, String key);
        void save(ValidationRequest request, String key, ValidationBundle.CommandReceipt receipt);
    }

    public interface Adapter {
        ValidationBundle.CommandReceipt command(ValidationRequest request, String checkId);
        ValidationReport finish(ValidationRequest request, List<ValidationBundle.CommandReceipt> commands);
        boolean artifactPresent(ValidationBundle.CommandReceipt command);
    }

    private final ObjectMapper mapper;
    private final Receipts receipts;

    public ValidationBundles(ObjectMapper mapper, Receipts receipts) {
        this.mapper = mapper;
        this.receipts = receipts;
    }

    public String commandIdentity(ValidationRequest request, String id) {
        return ContractStore.hash(mapper.writeValueAsString(List.of("validation-v1", request.runId(), request.projectId(),
                request.contractId(), request.planRevision(), request.stagedRevision(), request.snapshotHash(), request.dependencyHash(),
                request.commandPlanHash(), request.criteriaHash(), request.capabilityRevision(), request.validatorRevision(), id)));
    }

    public ValidationBundle validateBundle(ValidationRequest request, Adapter adapter) {
        var commands = new ArrayList<ValidationBundle.CommandReceipt>();
        var checks = new LinkedHashMap<String, ValidationCheck>();
        boolean ready = true;
        String artifact = null;
        for (String id : List.of("TYPE_CHECK", "BUILD")) {
            if (!request.requiredChecks().contains(id)) {
                continue;
            }
            if (!ready) {
                checks.put(id, new ValidationCheck(id, ValidationCheck.Outcome.SKIPPED, true, "Compiler prerequisite did not pass",
                        ValidationCheck.SkipCause.PREREQUISITE_FAILED));
                continue;
            }
            String key = commandIdentity(request, id);
            var command = receipts.find(request, key);
            if (command != null) {
                requireIdentity(command, id, key);
            }
            if (command == null || command.status() != ValidationCheck.Outcome.PASS
                    || id.equals("BUILD") && !adapter.artifactPresent(command)) {
                command = adapter.command(request, id);
                requireIdentity(command, id, key);
            }
            ready = command.status() == ValidationCheck.Outcome.PASS;
            if (id.equals("BUILD")) {
                ready &= command.artifactHash() != null && !command.artifactHash().isBlank() && adapter.artifactPresent(command);
                if (ready) {
                    artifact = command.artifactHash();
                }
            }
            commands.add(command);
            var status = ready ? ValidationCheck.Outcome.PASS : command.status() == ValidationCheck.Outcome.PASS
                    ? ValidationCheck.Outcome.SKIPPED : command.status();
            checks.put(id, new ValidationCheck(id, status, true, ready ? "Bound compiler evidence" : "Compiler evidence unavailable or failed"));
            if (ready) {
                receipts.save(request, key, command);
            }
        }
        var report = adapter.finish(request, List.copyOf(commands));
        if (report == null || report.changeSetRevision() != request.stagedRevision()) {
            throw new IllegalStateException("Validation report revision differs from the staged identity");
        }
        for (var check : report.checks()) {
            if (List.of("TYPE_CHECK", "BUILD").contains(check.id())) {
                continue; // Only command receipts can prove these checks.
            }
            if (checks.putIfAbsent(check.id(), check) != null) {
                throw new IllegalStateException("Duplicate validation check");
            }
        }
        for (String required : new TreeSet<>(request.requiredChecks())) {
            checks.putIfAbsent(required, new ValidationCheck(required, ValidationCheck.Outcome.SKIPPED, true, "Required coverage was not reported"));
        }
        var diagnostics = new ArrayList<ProjectDiagnostic>(report.introducedDiagnostics());
        String revision = "sha256:" + ContractStore.hash(mapper.writeValueAsString(Map.of("request", request,
                "checks", checks, "diagnostics", diagnostics)));
        return new ValidationBundle(request, revision, List.copyOf(checks.values()), diagnostics, commands, artifact,
                ready && report.available() && report.commandSucceeded() && report.changeValidationPassed());
    }

    private static void requireIdentity(ValidationBundle.CommandReceipt command, String id, String key) {
        if (command == null || !command.id().equals(id) || !command.inputHash().equals(key)) {
            throw new IllegalStateException("Compiler receipt identity mismatch");
        }
    }
}
