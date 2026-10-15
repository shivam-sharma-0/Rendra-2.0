package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.contract.ContractValidation;
import com.code.pal.agent.contract.StaticAssets;
import com.code.pal.agent.contract.ValidationBundle;
import com.code.pal.agent.contract.ValidationBundle.CommandReceipt;
import com.code.pal.agent.contract.ValidationBundles;
import com.code.pal.agent.contract.ValidationReceipts;
import com.code.pal.agent.contract.ValidationRequest;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.exception.WorkflowStageException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S7 side of the S6 validation bundle: collects the server-owned identity of the staged change, runs the
 * trusted TYPE_CHECK/BUILD commands and, for the final gate, the full validation (structure, runtime/browser
 * acceptance, production baseline) on that same staged revision. The BUILD artifact is identified by the
 * exact sources, dependencies, commands and validator it was built from; the browser check rebuilds that
 * identity deterministically, and any change to it invalidates reuse.
 */
@Component
public class ContractBundleValidation implements ContractValidation {
    private static final String VALIDATOR = "s7-bundle-v1";
    private static final Set<String> SNAPSHOT_SKIP = Set.of(".codepal", ".git", "node_modules", "dist", "build");
    private static final List<String> MANIFESTS = List.of("package.json", "package-lock.json", "client/package.json",
            "client/package-lock.json", "server/package.json", "server/package-lock.json");
    private static final int MAX_SNAPSHOT_FILES = 5_000;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;

    private final ProjectValidationService validation;
    private final ChangeSetService changes;
    private final ProjectFiles files;
    private final ContractStore store;
    private final RunBehaviorCriteria criteria;
    private final ProjectLeaseService leases;
    private final ObjectMapper mapper;
    private final Map<UUID, String> compilations = new ConcurrentHashMap<>();

    public ContractBundleValidation(ProjectValidationService validation, ChangeSetService changes, ProjectFiles files,
            ContractStore store, RunBehaviorCriteria criteria, ProjectLeaseService leases, ObjectMapper mapper) {
        this.validation = validation;
        this.changes = changes;
        this.files = files;
        this.store = store;
        this.criteria = criteria;
        this.leases = leases;
        this.mapper = mapper;
    }

    @Override
    public ValidationBundle validateBundle(AgentContext ctx, AgentRun run, BuildContract contract, String batchId,
            Set<String> completedBatchIds) {
        var request = request(ctx, run, contract, batchId, completedBatchIds);
        // The service's command cache is per staged revision; a compilation identity that changed in place
        // (commands, dependencies, validator) must not reuse it.
        String compilation = digest(Arrays.asList(request.stagedRevision(), request.snapshotHash(), request.dependencyHash(),
                request.commandPlanHash(), request.capabilityRevision(), request.validatorRevision()));
        if (compilations.size() > 1_000) {
            compilations.clear();
        }
        String previous = compilations.put(run.getId(), compilation);
        if (previous != null && !previous.equals(compilation)) {
            validation.forgetResults(run.getId());
        }
        var bundles = new ValidationBundles(mapper,
                new ValidationReceipts(files, mapper, leases, current -> requireCurrent(ctx, current)));
        return bundles.validateBundle(request, new Adapter(ctx, request, bundles));
    }

    private ValidationRequest request(AgentContext ctx, AgentRun run, BuildContract contract, String batchId,
            Set<String> completed) {
        var view = changes.view(ctx);
        UUID project = ctx.projectId();
        List<String> typeCheck = validation.plannedCommand(ctx, ValidationOperation.TYPE_CHECK);
        List<String> build = validation.plannedCommand(ctx, ValidationOperation.BUILD);
        Set<String> required = new HashSet<>(Set.of("BUILD"));
        if (typeCheck != null) {
            required.add("TYPE_CHECK");
        }
        if (batchId == null) {
            required.addAll(Set.of("STRUCTURE", "RUNTIME", "BASELINE"));
        }
        var own = criteria.current(ctx);
        var acceptance = own != null ? own : criteria.regression(ctx);
        return new ValidationRequest(run.getId(), project, contract.id(), contract.planRevision(), batchId, view.revision(),
                snapshot(project, view.stagedRoot()), digest(manifests(project, view.stagedRoot())),
                digest(Arrays.asList(typeCheck, build)), digest(acceptance == null ? "none" : acceptance),
                "sha256:" + store.current(project).capabilityRevision(),
                VALIDATOR + ":" + Starter.TEMPLATE_VERSION + ":" + StaticAssets.revision(),
                batchId == null ? ValidationRequest.Phase.FINAL : ValidationRequest.Phase.BATCH, required, completed);
    }

    private final class Adapter implements ValidationBundles.Adapter {
        private final AgentContext ctx;
        private final ValidationRequest request;
        private final ValidationBundles bundles;
        private final Map<String, ValidationReport> reports = new LinkedHashMap<>();

        Adapter(AgentContext ctx, ValidationRequest request, ValidationBundles bundles) {
            this.ctx = ctx;
            this.request = request;
            this.bundles = bundles;
        }

        @Override
        public CommandReceipt command(ValidationRequest current, String checkId) {
            requireCurrent(ctx, current);
            var report = validation.run(ctx, ValidationOperation.valueOf(checkId), null);
            if (report.operation() != ValidationOperation.valueOf(checkId) || report.changeSetRevision() != current.stagedRevision()) {
                throw new WorkflowStageException("Compiler evidence belongs to another operation or staged revision");
            }
            reports.put(checkId, report);
            var status = !report.available() ? ValidationCheck.Outcome.SKIPPED
                    : report.commandSucceeded() && report.changeValidationPassed() ? ValidationCheck.Outcome.PASS
                            : ValidationCheck.Outcome.FAIL;
            String artifact = checkId.equals("BUILD") && status == ValidationCheck.Outcome.PASS ? artifact(current) : null;
            return new CommandReceipt(checkId, bundles.commandIdentity(current, checkId), status, artifact);
        }

        @Override
        public ValidationReport finish(ValidationRequest current, List<CommandReceipt> commands) {
            requireCurrent(ctx, current);
            boolean compiled = commands.stream().allMatch(command -> command.status() == ValidationCheck.Outcome.PASS);
            if (current.phase() == ValidationRequest.Phase.FINAL && compiled) {
                return validation.validateFinal(ctx);
            }
            var diagnostics = reports.values().stream().flatMap(report -> report.introducedDiagnostics().stream()).toList();
            boolean passed = compiled && diagnostics.isEmpty();
            // A compiler failure is repairable: the final checks it blocks are prerequisites, not missing coverage.
            List<ValidationCheck> blocked = new ArrayList<>();
            if (current.phase() == ValidationRequest.Phase.FINAL) {
                for (String id : List.of("BASELINE", "RUNTIME", "STRUCTURE")) {
                    blocked.add(new ValidationCheck(id, ValidationCheck.Outcome.SKIPPED, true, "Compiler checks did not pass",
                            ValidationCheck.SkipCause.PREREQUISITE_FAILED));
                }
            }
            return new ValidationReport(ValidationOperation.BUILD, true, passed, passed, false, passed ? 0 : 1, "bound",
                    current.phase() == ValidationRequest.Phase.FINAL ? "final prerequisites" : "batch compiler", diagnostics,
                    List.of(), 0, 0, current.stagedRevision(), passed ? "Compiler checks passed" : "Compiler checks did not pass",
                    blocked);
        }

        @Override
        public boolean artifactPresent(CommandReceipt command) {
            var view = changes.view(ctx);
            return command.artifactHash() != null && command.artifactHash().equals(artifact(request))
                    && view.revision() == request.stagedRevision()
                    && snapshot(ctx.projectId(), view.stagedRoot()).equals(request.snapshotHash());
        }
    }

    private String artifact(ValidationRequest request) {
        return digest(List.of("artifact-v1", request.snapshotHash(), request.dependencyHash(), request.commandPlanHash(),
                request.validatorRevision()));
    }

    private void requireCurrent(AgentContext ctx, ValidationRequest request) {
        if (changes.view(ctx).revision() != request.stagedRevision() || !request.projectId().equals(ctx.projectId())) {
            throw new WorkflowStageException("Staged changes moved during validation; validate the current revision");
        }
    }

    private String snapshot(UUID project, String root) {
        var versions = new TreeMap<String, String>();
        for (var entry : files.list(project, root, 32, SNAPSHOT_SKIP)) {
            if (entry.directory()) {
                continue;
            }
            if (versions.size() >= MAX_SNAPSHOT_FILES) {
                throw new WorkflowStageException("Project is too large for bound validation evidence");
            }
            versions.put(entry.path(), files.version(project, root, entry.path()));
        }
        return digest(versions);
    }

    private Map<String, String> manifests(UUID project, String root) {
        var contents = new TreeMap<String, String>();
        for (String manifest : MANIFESTS) {
            if (files.regularFile(project, root, manifest)) {
                contents.put(manifest, new String(files.readBytes(project, root, manifest, MAX_MANIFEST_BYTES),
                        StandardCharsets.UTF_8));
            }
        }
        return contents;
    }

    private String digest(Object value) {
        return "sha256:" + ContractStore.hash(mapper.writeValueAsString(value));
    }
}
