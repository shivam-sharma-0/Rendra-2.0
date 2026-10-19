package com.code.pal.agent.contract;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.route.*;
import com.code.pal.agent.repair.StructuredRepair;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.exception.WorkflowStageException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/** Structured route; one coordinator owns mutation, validation and final save authority. */
@Service
public final class ContractCoordinator {
    private final ContractStore store;
    private final ContractPlanner planner;
    private final ContractScaffold scaffold;
    private final UnitGenerator generator;
    private final ApprovedProjectMemory memory;
    private final BuildDecisions decisions;
    private final ProjectFiles files;
    private final ChangeSetService changes;
    private final ProjectValidationService validation;
    private final RunBehaviorCriteria criteria;
    private final SequentialCheckpoints checkpoints;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final UnitWaveExecutor waves = new UnitWaveExecutor();
    private UnitOutputCheckpoints unitCheckpoints;
    private ContractValidation bundleValidation;
    private StructuredRepair structuredRepair;
    private ContractFixes deterministicFixes;

    @Autowired(required = false)
    public void useDeterministicFixes(ContractFixes adapter) {
        this.deterministicFixes = adapter;
    }

    @Autowired(required = false)
    public void useStructuredRepair(StructuredRepair repair) {
        this.structuredRepair = repair;
    }

    @Autowired(required = false)
    public void useBundleValidation(ContractValidation adapter) {
        this.bundleValidation = adapter;
    }

    @Autowired
    public void useUnitCheckpoints(UnitOutputCheckpoints checkpoints) {
        this.unitCheckpoints = checkpoints;
    }

    public ContractCoordinator(ContractStore store, ContractPlanner planner, ContractScaffold scaffold,
            UnitGenerator generator, ApprovedProjectMemory memory,
            BuildDecisions decisions, ProjectFiles files, ChangeSetService changes, ProjectValidationService validation,
            RunBehaviorCriteria criteria,
            SequentialCheckpoints checkpoints, AgentProperties props, ObjectMapper mapper) {
        this.store = store;
        this.planner = planner;
        this.scaffold = scaffold;
        this.generator = generator;
        this.memory = memory;
        this.decisions = decisions;
        this.files = files;
        this.changes = changes;
        this.validation = validation;
        this.criteria = criteria;
        this.checkpoints = checkpoints;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean eligible(AgentRun run, RunSize size, String request) {
        if (RequestRouter.route(request) != RequestRouter.Route.CHANGE) {
            return false;
        }
        var approved = memory.current(run.getProjectId());
        var choice = approved == null ? null : decisions.state(run.getProjectId()).choice();
        boolean fullStack = props.isFullStackContracts() && choice != null
                && choice.storage() == BuildDecisions.Storage.MERN;
        var attached = store.forRun(run.getProjectId(), run.getId());
        if (!props.isStructuredContracts() && !fullStack && unitCheckpoints != null && attached != null) {
            throw new WorkflowStageException(
                    "This run needs structured execution; restore its reviewed configuration before resuming");
        }
        boolean acceptedPlan = attached != null && "APPROVED".equals(attached.state())
                || store.acceptedPlanRun(run) != null;
        return (props.isStructuredContracts() || fullStack) && approved != null
                && (run.getMode() == ProjectType.PLAN || run.getMode() == ProjectType.BUILD
                        && (fullStack || size.tier() != SizeTier.S || RequestRouter.buildApprovedPlan(request)
                                || acceptedPlan));
    }

    public record Outcome(BuildContract contract, String summary) {
    }

    /** S4 supplies this server-loaded approved envelope, never model text or a client approval flag. */
    public record SpecPlanning(ApprovedSpec spec, BatchBudget.Limits budget) {
        public SpecPlanning {
            Objects.requireNonNull(spec);
            Objects.requireNonNull(budget);
        }
    }

    /** A resumed or explicitly accepted v2 contract still needs its approved spec on a nonempty stage. */
    public boolean needsSpecPlanning(AgentRun run) {
        var attached = store.forRun(run.getProjectId(), run.getId());
        if (attached != null && attached.contract().schemaVersion() == 2) {
            return true;
        }
        UUID accepted = store.acceptedPlanRun(run);
        var offered = accepted == null ? null : store.forRun(run.getProjectId(), accepted);
        return offered != null && offered.contract().schemaVersion() == 2;
    }

    public boolean eligible(AgentRun run, RunSize size, String request, SpecPlanning planning) {
        if (planning == null) {
            return eligible(run, size, request);
        }
        return props.isStructuredContracts() && RequestRouter.route(request) == RequestRouter.Route.CHANGE
                && (run.getMode() == ProjectType.BUILD || run.getMode() == ProjectType.PLAN)
                && planning.spec().projectId().equals(run.getProjectId()) && memory.current(run.getProjectId()) != null;
    }

    public Outcome execute(AgentContext ctx, AgentRun run, RunSize size, String request, String projectMap,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall, Runnable beforeIo,
            Consumer<String> activity) {
        return execute(ctx, run, size, request, projectMap, checkedCall, beforeIo, activity, null);
    }

    public Outcome execute(AgentContext ctx, AgentRun run, RunSize size, String request, String projectMap,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall, Runnable beforeIo,
            Consumer<String> activity, SpecPlanning planning) {
        var allowed = new BuildContract.Limits(size.maxModelCalls(), size.maxToolCalls(),
                props.getLlm().getMaxOutputTokens(), size.timeoutMs());
        var basis = store.current(ctx.projectId());
        var approved = memory.current(ctx.projectId());
        if (planning != null) {
            requireSpec(ctx, basis, planning.spec());
        }
        var stored = store.forRun(ctx.projectId(), run.getId());
        UUID acceptedPlan = run.getMode() == ProjectType.BUILD ? store.acceptedPlanRun(run) : null;
        if (stored == null && run.getMode() == ProjectType.BUILD
                && (RequestRouter.buildApprovedPlan(request) || acceptedPlan != null)) {
            var reuse = store.reusable(ctx.projectId(), request, allowed);
            if (acceptedPlan != null && (reuse == null || !acceptedPlan.equals(reuse.runId()))) {
                throw new WorkflowStageException(
                        "The accepted plan is unavailable or superseded; review the current plan before Build");
            }
            if (reuse != null) {
                stored = store.attach(run, reuse, allowed);
            }
        }
        if (stored == null) {
            activity.accept("Planning implementation");
            var choice = decisions.state(ctx.projectId()).choice();
            String root = choice.storage() == BuildDecisions.Storage.MERN ? "client/src/App.tsx"
                    : choice.storage() == BuildDecisions.Storage.NONE ? null : "src/App.tsx";
            if (planning != null && planning.spec().desired().renderStack() == ApprovedSpec.RenderStack.VITE) {
                root = "src/App.tsx";
            }
            String requiredRoot = root != null && !files.regularFile(ctx.projectId(), ctx.root(), root) ? root : null;
            var input = new ContractPlanner.Input(UUID.randomUUID(), request, ApprovedProductScope.planningRequirements(approved, mapper), projectMap,
                    choice, allowed, requiredRoot);
            BuildContract contract;
            if (planning == null) {
                contract = planner.plan(input, props.preferredModel(ProjectType.PLAN), checkedCall,
                        proposal -> store.validateSources(ctx.projectId(), proposal));
            } else {
                var layout = new DeterministicPlan(mapper).plan(planning.spec(), planning.budget());
                contract = layout.isPresent() ? ContractPlans.fromLayout(input.id(), planning.spec(), layout.get(), allowed)
                        : planner.plan(new ContractPlanner.SpecInput(input, planning.spec(), planning.budget()),
                                props.preferredModel(ProjectType.PLAN), checkedCall,
                                proposal -> store.validateSources(ctx.projectId(), proposal));
                contract = ContractPlans.sealInterfaces(contract, render(ctx, contract));
                requireBasis(ctx, basis);
            }
            stored = store.create(run, contract, basis, allowed);
        }
        if (!stored.binding().equals(basis) || !stored.contract().limits().within(allowed)
                || "INVALIDATED".equals(stored.state()))
            throw new WorkflowStageException("Stored plan no longer matches this run");
        var c = stored.contract();
        if (planning != null && c.schemaVersion() != 2) {
            throw new WorkflowStageException("Review a spec-bound plan before using this approved spec");
        }
        if (c.schemaVersion() == 2) {
            requireCurrentPlan(c, planning);
        }
        if (run.getMode() == ProjectType.PLAN)
            return new Outcome(c,
                    "Implementation plan:\n"
                            + c.waves().stream().flatMap(List::stream)
                                    .map(u -> "- " + u.objective() + " (" + String.join(", ", u.ownedPaths()) + ")")
                                    .collect(Collectors.joining("\n"))
                            + "\n\nReview and approve this plan before asking to build it.");
        if (!"APPROVED".equals(stored.state()))
            throw new WorkflowStageException("Review and approve the plan before Build");
        checkpoints.restrict(run, c.limits(), size);
        ctx.plan().revise(c.waves().stream().flatMap(List::stream).map(BuildContract.Unit::objective).toList(),
                "Approved immutable build contract");
        beforeIo.run();
        activity.accept("Preparing shared interfaces");
        Map<String, String> common = render(ctx, c);
        if (c.schemaVersion() == 2 && !ContractPlans.sealInterfaces(c, common).equals(c)) {
            throw new WorkflowStageException("Shared template changed; review a current plan before building");
        }
        merge(ctx, common.entrySet().stream().map(e -> new UnitGenerator.Delta(e.getKey(),
                files.version(ctx.projectId(), ctx.root(), e.getKey()), e.getValue())).toList());
        for (var asset : c.assets()) {
            beforeIo.run();
            if (!files.version(ctx.projectId(), ctx.root(), asset.source()).equals(asset.fingerprint()))
                throw new WorkflowStageException("Asset source changed");
            if (files.version(ctx.projectId(), ctx.root(), asset.destination()).equals(asset.fingerprint()))
                continue;
            if (files.exists(ctx.projectId(), ctx.root(), asset.destination()))
                throw new WorkflowStageException("Asset destination needs a reviewed replacement");
            try (var scope = UnitWriteScope.wave(ctx, c,
                    c.units().stream().filter(u -> u.id().equals(asset.owner())).toList())) {
                changes.copy(ctx, asset.source(), asset.destination());
            }
        }
        criteria.install(run.getId(), ctx.projectId(), basis.requirementsProjection(), basis.requirementsRevision(),
                c.acceptance());
        var completedBatches = new HashSet<String>();
        var orderedBatches = c.schemaVersion() == 2 ? ContractPlans.ordered(c.batches()) : List.<ContractBatch>of();
        var executionWaves = c.schemaVersion() == 2 ? orderedBatches.stream().map(batch -> batch.unitIds().stream()
                .map(id -> c.units().stream().filter(unit -> unit.id().equals(id)).findFirst().orElseThrow()).toList()).toList() : c.waves();
        for (int waveIndex = 0; waveIndex < executionWaves.size(); waveIndex++) {
            var wave = executionWaves.get(waveIndex);
            if (c.schemaVersion() == 2) {
                beforeIo.run();
            }
            requireBasis(ctx, basis);
            var outputs = new ArrayList<UnitGenerator.Delta>();
            var inputs = new ArrayList<UnitGenerator.Input>();
            for (var unit : wave) {
                activity.accept(unit.objective());
                if (c.schemaVersion() == 1
                        && c.assets().stream().map(BuildContract.Asset::destination).toList().containsAll(unit.ownedPaths()))
                    continue;
                var in = input(ctx, c, unit, request, approved.requirementsJson(), common.keySet(), "");
                if (c.schemaVersion() == 2) {
                    inputs.add(in);
                    continue;
                }
                var cached = unitCheckpoints == null ? null : unitCheckpoints.reuse(run, in, ctx);
                if (cached == null)
                    inputs.add(in);
                else
                    outputs.addAll(cached.files());
            }
            int width = c.schemaVersion() == 1 && props.isParallelUnits() && inputs.size() > 1 ? 2 : 1;
            if (width == 2) {
                var scheduling = props.getScheduling();
                var model = props.preferredModel(ProjectType.BUILD);
                var pool = scheduling.getPools().get(model.provider());
                if (!scheduling.isEnabled() || scheduling.getGlobalConcurrency() < 2
                        || scheduling.getUserConcurrency() < 2 || scheduling.getRunConcurrency() < 2 || pool == null
                        || pool.getConcurrency() < 2)
                    throw new WorkflowStageException(
                            "Two-worker execution needs verified shared provider/account capacity");
            }
            var progress = checkpoints.progress(run.getId());
            if (progress == null)
                throw new WorkflowStageException("Durable wave deadline unavailable");
            var batching = new BatchUnitGenerator(mapper);
            if (c.schemaVersion() == 2) {
                var batch = orderedBatches.get(waveIndex);
                outputs.addAll(plannedOutputs(ctx, run, c, batch, inputs, completedBatches, planning, checkedCall));
            } else if (width == 1 && props.isBatchGeneration() && !props.isParallelUnits() && batching.eligible(inputs)) {
                var frozen = BatchUnitGenerator.checkpointInputs(inputs);
                var recovered = new ArrayList<UnitGenerator.Output>();
                if (unitCheckpoints != null)
                    for (var input : frozen) {
                        var cached = unitCheckpoints.reuse(run, input, ctx);
                        if (cached == null) {
                            recovered.clear();
                            break;
                        }
                        recovered.add(cached);
                    }
                List<UnitGenerator.Output> generated = recovered;
                if (recovered.size() != frozen.size()) {
                    try (var scope = UnitWriteScope.worker(ctx)) {
                        generated = batching.generate(frozen, props.preferredModel(ProjectType.BUILD), checkedCall);
                    }
                    for (int index = 0; index < frozen.size(); index++)
                        if (unitCheckpoints != null)
                            unitCheckpoints.save(run, frozen.get(index), generated.get(index));
                }
                for (var output : generated)
                    outputs.addAll(output.files());
            } else
                for (var output : waves.generate(inputs, width, progress.remainingMs(), in -> {
                    try (var scope = UnitWriteScope.worker(ctx)) {
                        var result = generator.generate(in, props.preferredModel(ProjectType.BUILD), checkedCall);
                        if (unitCheckpoints != null)
                            unitCheckpoints.save(run, in, result);
                        return result;
                    }
                }))
                    outputs.addAll(output.files());
            beforeIo.run();
            requireBasis(ctx, basis);
            try (var scope = UnitWriteScope.wave(ctx, c, wave)) {
                merge(ctx, outputs);
            }
            if (c.schemaVersion() == 2 && structuredRepair != null) {
                var ready = new HashSet<String>();
                orderedBatches.stream().filter(batch -> completedBatches.contains(batch.id())).forEach(batch -> ready.addAll(batch.ownedPaths()));
                wave.forEach(unit -> ready.addAll(unit.ownedPaths()));
                beforeIo.run();
                structuredRepair.recoverReady(ctx, run, c, ready);
            }
            // Intermediate compiler checks never certify incomplete acceptance or a pending unit.
            if (c.schemaVersion() == 2 && bundleValidation != null) {
                beforeIo.run();
                for (;;) {
                    var report = bundleReport(ctx, run, c, orderedBatches.get(waveIndex).id(), completedBatches);
                    if (report.changeValidationPassed()) {
                        break;
                    }
                    if (report.checks().stream().anyMatch(ValidationCheck::unavailable)) {
                        throw new WorkflowStageException("Required batch checks are unavailable");
                    }
                    repair(ctx, run, c, wave, request, approved.requirementsJson(), common.keySet(), report, checkedCall, beforeIo, activity, planning);
                    beforeIo.run();
                }
                completedBatches.add(orderedBatches.get(waveIndex).id());
                continue;
            }
            beforeIo.run();
            var report = validation.run(ctx, ValidationOperation.TYPE_CHECK, null);
            if (report.available() && !report.changeValidationPassed())
                repair(ctx, run, c, wave, request, approved.requirementsJson(), common.keySet(), report, checkedCall,
                        beforeIo, activity, planning);
            beforeIo.run();
            report = validation.run(ctx, ValidationOperation.BUILD, null);
            if (report.available() && !report.changeValidationPassed())
                repair(ctx, run, c, wave, request, approved.requirementsJson(), common.keySet(), report, checkedCall,
                        beforeIo, activity, planning);
            if (c.schemaVersion() == 2) {
                completedBatches.add(orderedBatches.get(waveIndex).id());
            }
        }
        for (int attempt = 0;; attempt++) {
            beforeIo.run();
            requireBasis(ctx, basis);
            ContractCompletion.require(c, path -> files.regularFile(ctx.projectId(), ctx.root(), path) ? bounded(ctx, path) : null);
            activity.accept("Checking required behavior");
            var report = c.schemaVersion() == 2 && bundleValidation != null
                    ? bundleReport(ctx, run, c, null, completedBatches) : validation.validateAll(ctx);
            if (report.changeValidationPassed() && c.acceptance().api().stream()
                    .anyMatch(step -> report.checks().stream().noneMatch(check -> check.id().equals("API_" + step.id())
                            && check.outcome() == ValidationCheck.Outcome.PASS)))
                throw new WorkflowStageException(
                        "Required API/account acceptance is unavailable for this connector; staged code was not saved");
            if (report.changeValidationPassed() && report.checks().stream()
                    .anyMatch(check -> check.id().equals("RUNTIME") && check.outcome() == ValidationCheck.Outcome.PASS))
                break;
            if (report.changeValidationPassed())
                throw new WorkflowStageException(
                        "Required browser acceptance is unavailable; staged code was not saved");
            if (report.checks().stream().anyMatch(ValidationCheck::unavailable)
                    || changes.validationProgress(ctx).repairLimitExhausted()
                    || attempt >= props.getMaxRepairAttempts())
                throw new WorkflowStageException("Required application checks did not pass; staged code was not saved");
            var affected = affected(c, report);
            repair(ctx, run, c, affected, request, approved.requirementsJson(), common.keySet(), report, checkedCall,
                    beforeIo, activity, planning);
        }
        int step = 1;
        for (var unit : c.waves().stream().flatMap(List::stream).toList())
            ctx.plan().updateStep(step++, AgentPlanState.StepStatus.COMPLETED, "Required backend checks passed");
        if (unitCheckpoints != null)
            unitCheckpoints.verified(run);
        return new Outcome(c, "The requested application changes passed the required checks.");
    }

    private void repair(AgentContext ctx, AgentRun run, BuildContract c, List<BuildContract.Unit> units, String request,
            String req, Set<String> shared, ValidationReport report,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checked, Runnable beforeIo, Consumer<String> activity, SpecPlanning planning) {
        if (c.schemaVersion() == 2) {
            int beforeFix = changes.view(ctx).revision();
            if (deterministicFixes != null && deterministicFixes.tryFix(ctx, run, c, report)) {
                if (changes.view(ctx).revision() <= beforeFix) {
                    throw new WorkflowStageException("Deterministic fixer returned success without a staged revision change");
                }
                return;
            }
            if (structuredRepair == null) {
                throw new WorkflowStageException("Scoped patch repair is unavailable");
            }
            activity.accept("Fixing the failing code");
            structuredRepair.repair(ctx, run, c, units, report, checked, beforeIo, planning == null ? null : planning.spec());
            return;
        }
        if (changes.validationProgress(ctx).repairLimitExhausted())
            throw new WorkflowStageException("Repair limit reached");
        var output = new ArrayList<UnitGenerator.Delta>();
        String feedback = mapper
                .writeValueAsString(Map.of("diagnostics", report.introducedDiagnostics(), "checks", report.checks()));
        var repairInputs = units.stream().map(unit -> input(ctx, c, unit, request, req, shared, feedback)).toList();
        var batching = new BatchUnitGenerator(mapper);
        if (props.isBatchGeneration() && !props.isParallelUnits() && batching.eligible(repairInputs)) {
            var frozen = BatchUnitGenerator.checkpointInputs(repairInputs);
            List<UnitGenerator.Output> generated;
            activity.accept("Repairing affected features");
            try (var scope = UnitWriteScope.worker(ctx)) {
                generated = batching.generate(frozen, props.preferredModel(ProjectType.BUILD), checked);
            }
            for (int index = 0; index < frozen.size(); index++) {
                if (unitCheckpoints != null)
                    unitCheckpoints.save(run, frozen.get(index), generated.get(index));
                output.addAll(generated.get(index).files());
            }
        } else
            for (var input : repairInputs) {
                activity.accept("Repairing " + input.unit().objective());
                try (var scope = UnitWriteScope.worker(ctx)) {
                    var result = generator.generate(input, props.preferredModel(ProjectType.BUILD), checked);
                    if (unitCheckpoints != null)
                        unitCheckpoints.save(run, input, result);
                    output.addAll(result.files());
                }
            }
        beforeIo.run();
        try (var scope = UnitWriteScope.wave(ctx, c, units)) {
            merge(ctx, output);
        }
    }

    static List<BuildContract.Unit> affected(BuildContract c, ValidationReport report) {
        var ids = new HashSet<String>();
        var failed = ContractAcceptanceFailures.ids(c, report);
        c.units().stream().filter(unit -> unit.acceptanceIds().stream().anyMatch(failed::contains)).forEach(unit -> ids.add(unit.id()));
        for (var diagnostic : report.introducedDiagnostics()) {
            if (diagnostic.file() != null) {
                String owner = c.ownership().get(diagnostic.file());
                if (owner != null)
                    ids.add(owner);
            }
        }
        if (ids.isEmpty() && c.schemaVersion() == 1)
            ids.addAll(c.units().stream().map(BuildContract.Unit::id).toList());
        // Shared interfaces are immutable. A dependency edge alone does not prove a dependent needs
        // regeneration; fresh whole-project validation will identify any actual downstream failure.
        return c.waves().stream().flatMap(List::stream).filter(u -> ids.contains(u.id())).toList();
    }

    private UnitGenerator.Input input(AgentContext ctx, BuildContract c, BuildContract.Unit unit, String request,
            String req, Set<String> shared, String feedback) {
        var paths = new TreeSet<String>(unit.ownedPaths());
        paths.addAll(unit.imports());
        c.units().stream().filter(u -> unit.dependsOn().contains(u.id())).forEach(u -> paths.addAll(u.ownedPaths()));
        paths.addAll(shared.stream().filter(p -> p.startsWith(c.clientRoot()) || p.startsWith(c.serverRoot()))
                .filter(p -> !p.endsWith("contract.json")).toList());
        var assets = new HashSet<String>();
        c.assets().forEach(a -> assets.add(a.destination()));
        var bodies = Set.copyOf(paths);
        if (c.schemaVersion() == 2) {
            paths.addAll(ContractImports.availablePaths(c, unit));
        }
        var snapshot = new TreeMap<String, UnitGenerator.FileView>();
        for (String path : paths) {
            if (assets.contains(path))
                continue;
            String version = files.version(ctx.projectId(), ctx.root(), path);
            snapshot.put(path, new UnitGenerator.FileView(version,
                    bodies.contains(path) && files.regularFile(ctx.projectId(), ctx.root(), path) ? bounded(ctx, path) : null));
        }
        return new UnitGenerator.Input(c, unit, request, req, snapshot, feedback);
    }

    private String bounded(AgentContext ctx, String path) {
        byte[] bytes = files.readBytes(ctx.projectId(), ctx.root(), path, 1_000_000);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0)
                throw new WorkflowStageException("Binary asset needs declared source identity");
            return text;
        } catch (CharacterCodingException binary) {
            throw new WorkflowStageException("Binary asset needs declared source identity");
        }
    }

    private void merge(AgentContext ctx, List<UnitGenerator.Delta> files) {
        if (!files.isEmpty())
            changes.replaceBatch(ctx, files.stream()
                    .map(f -> new ChangeSetService.Replacement(f.path(), f.expectedVersion(), f.content())).toList());
    }

    private void requireBasis(AgentContext ctx, ContractStore.Binding expected) {
        if (!store.current(ctx.projectId()).equals(expected))
            throw new WorkflowStageException("Approval/code/capabilities changed; create a current plan");
    }

    private ValidationReport bundleReport(AgentContext ctx, AgentRun run, BuildContract contract, String batchId, Set<String> completed) {
        var bundle = bundleValidation.validateBundle(ctx, run, contract, batchId, Set.copyOf(completed));
        var identity = bundle.requestIdentity();
        if (!identity.runId().equals(run.getId()) || !identity.projectId().equals(ctx.projectId())
                || !identity.contractId().equals(contract.id()) || identity.planRevision() != contract.planRevision()
                || !Objects.equals(identity.batchId(), batchId) || identity.stagedRevision() != changes.view(ctx).revision()
                || !identity.completedBatchIds().equals(completed)
                || batchId == null && !identity.requiredChecks().containsAll(Set.of("BUILD", "RUNTIME", "BASELINE"))
                || identity.phase() != (batchId == null ? ValidationRequest.Phase.FINAL : ValidationRequest.Phase.BATCH)) {
            throw new WorkflowStageException("Validation bundle does not match the staged contract revision");
        }
        boolean passed = bundle.checks().stream().noneMatch(check -> check.blocksSuccess()
                || identity.requiredChecks().contains(check.id()) && check.outcome() != ValidationCheck.Outcome.PASS);
        passed &= bundle.checks().stream().map(ValidationCheck::id).collect(Collectors.toSet()).containsAll(identity.requiredChecks());
        passed &= bundle.diagnostics().stream().noneMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
        if (batchId == null) {
            passed &= bundle.certifyEligible();
        }
        return new ValidationReport(ValidationOperation.BUILD, true, passed, passed, false, passed ? 0 : 1,
                "bound", "validation bundle", bundle.diagnostics(), List.of(), 0, 2, identity.stagedRevision(),
                bundle.diagnosticsRevision(), bundle.checks());
    }

    private Map<String, String> render(AgentContext ctx, BuildContract contract) {
        return scaffold.render(contract, "App", path -> files.regularFile(ctx.projectId(), ctx.root(), path) ? bounded(ctx, path) : null,
                ctx.projectId());
    }

    private void requireSpec(AgentContext ctx, ContractStore.Binding basis, ApprovedSpec spec) {
        var state = decisions.state(ctx.projectId());
        if (!spec.projectId().equals(ctx.projectId()) || !spec.requirementsProjection().equals(basis.requirementsProjection())
                || spec.requirementsRevision() != basis.requirementsRevision()
                || !(spec.capabilityRevision().equals(basis.capabilityRevision())
                        || spec.capabilityRevision().equals("sha256:" + basis.capabilityRevision()))
                || spec.readiness() != ApprovedSpec.Readiness.READY || state.blocked() || state.choice() == null
                || !spec.desired().storage().name().equals(state.choice().storage().name())
                || !spec.desired().auth().name().equals(state.choice().auth().name())) {
            throw new WorkflowStageException("Approved spec no longer matches current requirements/capabilities");
        }
    }

    private void requireCurrentPlan(BuildContract contract, SpecPlanning planning) {
        if (planning == null || !planning.spec().specId().equals(contract.specId())
                || planning.spec().specRevision() != contract.specRevision() || !planning.spec().specHash().equals(contract.specHash())
                || !Starter.TEMPLATE_VERSION.equals(contract.templateVersion())) {
            throw new WorkflowStageException("Load the current approved spec/template before executing this versioned plan");
        }
        if (!ContractPlans.splitOversized(contract, planning.budget(), mapper).equals(contract)) {
            throw new WorkflowStageException("Stored plan needs a versioned repartition and fresh review");
        }
    }

    private List<UnitGenerator.Output> recoverBatch(AgentRun run, AgentContext ctx, List<UnitGenerator.Input> inputs) {
        if (unitCheckpoints == null) {
            return List.of();
        }
        var cached = structuredRepair == null ? unitCheckpoints.reuseBatch(run, inputs, ctx)
                : unitCheckpoints.reuseBatch(run, inputs, ctx, outputs -> structuredRepair.overlay(ctx, run, inputs.getFirst().contract(), outputs));
        return cached == null ? List.of() : cached;
    }

    private List<UnitGenerator.Delta> plannedOutputs(AgentContext ctx, AgentRun run, BuildContract contract, ContractBatch batch,
            List<UnitGenerator.Input> inputs, Set<String> completed, SpecPlanning planning,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checked) {
        // Partial cache hits never shrink a batch into a different, unapproved request. The receipt
        // binds exactly the snapshot supplied to generation, rather than a larger unsent context.
        var frozen = BatchUnitGenerator.plannedInputs(contract, batch, inputs);
        var generated = recoverBatch(run, ctx, frozen);
        if (generated.size() != frozen.size()) {
            try (var scope = UnitWriteScope.worker(ctx)) {
                generated = new BatchUnitGenerator(mapper).generatePlanned(contract, batch, frozen, completed,
                        planning.spec(), props.preferredModel(ProjectType.BUILD), checked);
            }
            if (unitCheckpoints != null) {
                unitCheckpoints.saveBatch(run, frozen, generated);
            }
            if (structuredRepair != null) {
                generated = structuredRepair.overlay(ctx, run, contract, generated);
            }
        }
        return generated.stream().flatMap(output -> output.files().stream()).toList();
    }
}
