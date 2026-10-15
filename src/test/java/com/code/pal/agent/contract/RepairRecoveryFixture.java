package com.code.pal.agent.contract;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.FileChange;
import com.code.pal.agent.change.FileChangeRepository;
import com.code.pal.agent.change.SequentialCheckpoints;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repair.RepairJournal;
import com.code.pal.agent.repair.RepairPatch;
import com.code.pal.agent.repair.StructuredRepair;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.route.SizeTier;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.usage.DailyTokenBudget;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real disposable SQL, leases, accounting, files and apply; only the sandbox transport/model are substituted. */
final class RepairRecoveryFixture {
    enum Cut {
        BEFORE_RESERVE, AFTER_RESERVE, BEFORE_PREPARE, AFTER_PREPARE, AFTER_FIRST_FILE, DURING_AUDIT, BEFORE_APPLIED, AFTER_APPLIED
    }

    static final class ProcessLoss extends Error {
        ProcessLoss(Cut cut) {
            super("Synthetic process loss: " + cut);
        }
    }

    final ContractStoreIT helper;
    final SpecStoreFixture spec;
    final AgentRun run;
    final ProjectLeaseService leases;
    final SequentialCheckpoints checkpoints;
    final UnitOutputCheckpoints outputs;
    final DailyTokenBudget budget;
    final ProjectFiles files;
    final AtomicInteger modelResponses = new AtomicInteger();
    final List<UnitGenerator.Input> inputs;
    final AnnotationConfigApplicationContext app;
    RunFence fence;
    AgentContext ctx;
    ChangeSetService changes;
    StructuredRepair repairs;
    Cut cut;
    boolean fired;

    RepairRecoveryFixture(AnnotationConfigApplicationContext app, ContractStoreIT helper) {
        this.app = app;
        this.helper = helper;
        spec = new SpecStoreFixture(helper);
        run = spec.base.run(ProjectType.BUILD);
        spec.base.done();
        run.setRequestFingerprint("b".repeat(64));
        helper.jdbc.update("update agent_runs set request_fingerprint=? where id=?", run.getRequestFingerprint(), run.getId());
        leases = app.getBean(ProjectLeaseService.class);
        checkpoints = app.getBean(SequentialCheckpoints.class);
        outputs = app.getBean(UnitOutputCheckpoints.class);
        budget = app.getBean(DailyTokenBudget.class);
        files = spy(helper.files);
        interceptWrites();
        fence = leases.claim(spec.base.project, run.getId(), UUID.randomUUID());
        assertNotNull(fence);
        var snapshot = Map.of("index.html", new UnitGenerator.FileView(version(".", "index.html"), null),
                "styles.css", new UnitGenerator.FileView(version(".", "styles.css"), null));
        inputs = spec.contract.units().stream().map(unit -> new UnitGenerator.Input(spec.contract, unit,
                "Build bakery", "Approved", snapshot, "")).toList();
        try (var bound = RunFence.bind(fence)) {
            helper.store.create(run, spec.contract, spec.basis, spec.contract.limits());
            checkpoints.configure(run, new RunSize(SizeTier.S, List.of("fixture"), 300_000, 10, 10, 30));
            newWorker();
            var generated = inputs.stream().map(input -> new UnitGenerator.Output(input.unit().ownedPaths().stream()
                    .map(path -> new UnitGenerator.Delta(path, snapshot.get(path).version(),
                            path.equals("index.html") ? "<h1>Broken</h1>" : "body { color: red; }")).toList())).toList();
            outputs.saveBatch(run, inputs, generated);
            apply(generated);
        }
    }

    private void interceptWrites() {
        doAnswer(call -> {
            String root = call.getArgument(1);
            String path = call.getArgument(2);
            String content = call.getArgument(3);
            boolean journal = root.equals(journalRoot()) && path.equals("journal.json");
            String state = journal ? helper.mapper.readValue(content, RepairJournal.Envelope.class).state().rounds().getLast().state() : "";
            trip(switch (state) {
                case "RESERVED" -> Cut.BEFORE_RESERVE;
                case "PREPARED" -> Cut.BEFORE_PREPARE;
                case "APPLIED" -> Cut.BEFORE_APPLIED;
                default -> null;
            });
            Object result = call.callRealMethod();
            if (journal) {
                trip(switch (state) {
                    case "RESERVED" -> Cut.AFTER_RESERVE;
                    case "PREPARED" -> Cut.AFTER_PREPARE;
                    case "APPLIED" -> Cut.AFTER_APPLIED;
                    default -> null;
                });
            } else if (ctx != null && root.equals(ctx.root()) && path.equals("index.html") && content.contains("Fixed")) {
                trip(Cut.AFTER_FIRST_FILE);
            }
            return result;
        }).when(files).writeText(eq(run.getProjectId()), anyString(), anyString(), anyString());
    }

    private void trip(Cut point) {
        if (point != null && point == cut && !fired) {
            fired = true;
            throw new ProcessLoss(point);
        }
    }

    private void newWorker() {
        SandboxPort sandbox = mock(SandboxPort.class);
        doAnswer(call -> {
            for (String path : List.of("index.html", "styles.css")) {
                if (files.regularFile(run.getProjectId(), call.getArgument(1), path)) {
                    files.copyFile(run.getProjectId(), call.getArgument(1), path, call.getArgument(2), path);
                }
            }
            return null;
        }).when(sandbox).copyTree(eq(run.getProjectId()), anyString(), anyString());
        var realAudit = app.getBean(FileChangeRepository.class);
        FileChangeRepository audit = mock(FileChangeRepository.class);
        doAnswer(call -> {
            Iterable<FileChange> values = call.getArgument(0);
            var rows = new ArrayList<FileChange>();
            values.forEach(rows::add);
            if (cut == Cut.DURING_AUDIT && !fired) {
                realAudit.saveAndFlush(rows.getFirst());
                trip(Cut.DURING_AUDIT);
            }
            return realAudit.saveAllAndFlush(rows);
        }).when(audit).saveAllAndFlush(any());
        changes = new ChangeSetService(audit, app.getBean(AgentRunRepository.class), helper.mapper,
                app.getBean(AgentProperties.class), files, sandbox, app.getBean(ProjectService.class));
        ReflectionTestUtils.setField(changes, "leases", leases);
        ctx = new AgentContext(run.getProjectId(), 7L, ProjectType.BUILD, changes.start(run.getId(), run.getProjectId()),
                null, new ProjectContextTrace(), run.getId(), new AgentPlanState(), files);
        ctx.plan().revise(List.of("Build bakery"), "Disposable fixture");
        repairs = new StructuredRepair(files, changes, helper.store, helper.jdbc, helper.mapper,
                app.getBean(AgentProperties.class), leases);
    }

    void takeover() {
        helper.jdbc.update("update agent_project_leases set expires_at=clock_timestamp()-interval '1 second' where project_id=?", run.getProjectId());
        fence = leases.claim(run.getProjectId(), run.getId(), UUID.randomUUID());
        assertNotNull(fence);
    }

    void restartAndRecover() {
        takeover();
        try (var bound = RunFence.bind(fence)) {
            newWorker();
            var replay = outputs.reuseBatch(run, inputs, ctx, generated -> repairs.overlay(ctx, run, spec.contract, generated));
            assertNotNull(replay, "Whole generation checkpoint survives service/worker recreation");
            apply(replay);
            repairs.recover(ctx, run, spec.contract);
            repairs.recover(ctx, run, spec.contract);
        }
    }

    private void apply(List<UnitGenerator.Output> generated) {
        var replacements = generated.stream().flatMap(output -> output.files().stream())
                .map(delta -> new ChangeSetService.Replacement(delta.path(), delta.expectedVersion(), delta.content())).toList();
        if (!replacements.isEmpty()) {
            try (var scope = UnitWriteScope.wave(ctx, spec.contract, spec.contract.units())) {
                changes.replaceBatch(ctx, replacements);
            }
        }
    }

    void repair() {
        repairs.repair(ctx, run, spec.contract, spec.contract.units(), report(), this::model, () -> leases.checkCurrent(run.getProjectId()));
    }

    LlmClient.LlmResult model(LlmClient.LlmRequest request) {
        assertTrue(checkpoints.reserveModelIteration(run));
        modelResponses.incrementAndGet();
        charge(UUID.randomUUID(), new LlmClient.Usage(12, 3), "OK");
        assertTrue(request.tools().isEmpty());
        var data = helper.mapper.readTree(request.messages().get(1).content());
        var patches = new ArrayList<RepairPatch.FilePatch>();
        for (String path : List.of("index.html", "styles.css")) {
            var file = data.path("files").path(path);
            String body = file.path("content").asString();
            String search;
            if (path.equals("index.html")) {
                search = body.contains("Broken") ? "Broken" : "Fixed";
            } else {
                search = body.contains("red") ? "red" : "blue";
            }
            String replacement = switch (search) {
                case "Broken" -> "Fixed";
                case "Fixed" -> "Final";
                case "red" -> "blue";
                default -> "green";
            };
            patches.add(new RepairPatch.FilePatch(path, file.path("version").asString(), List.of(new RepairPatch.Edit(search, replacement))));
        }
        var patch = new RepairPatch(1, data.path("batchId").asString(), data.path("diagnosticsRevision").asString(), patches);
        return new LlmClient.LlmResult(helper.mapper.writeValueAsString(patch), null, List.of(), new LlmClient.Usage(12, 3), "stop");
    }

    void charge(UUID logical, LlmClient.Usage usage, String outcome) {
        var owner = new LlmCallContext(7L, run.getProjectId(), run.getId(), CallPurpose.OTHER, true);
        var grant = budget.reserve(new DailyTokenBudget.Request(UUID.randomUUID(), logical, owner,
                "synthetic", "no-network", 100, 50, 10_000_000));
        assertTrue(grant.granted());
        budget.markSent(grant.id());
        budget.finishAttempt(grant.id(), usage, outcome, usage == null ? "synthetic unknown completion" : null, 1);
    }

    ValidationReport report() {
        var diagnostics = List.of("index.html", "styles.css").stream().map(path -> new ProjectDiagnostic(path, null, null,
                DiagnosticSeverity.ERROR, "Repair the existing bakery", "fixture", "FIX_BAKERY")).toList();
        return new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "synthetic", "fixture", diagnostics,
                List.of(), 0, 2, changes.view(ctx).revision(), "Failed", List.of());
    }

    RepairJournal.Envelope receipt() {
        return helper.mapper.readValue(files.readText(run.getProjectId(), journalRoot(), "journal.json"), RepairJournal.Envelope.class);
    }

    String journalRoot() { return ".codepal/repairs/" + run.getId(); }
    String version(String root, String path) { return files.version(run.getProjectId(), root, path); }
    String body(String path) { return files.readText(run.getProjectId(), ctx.root(), path); }
    int ledgerCount() { return helper.jdbc.queryForObject("select count(*) from agent_model_calls where run_id=?", Integer.class, run.getId()); }

    long chargedTokens() {
        return helper.jdbc.queryForObject("select coalesce(sum(prompt_tokens+completion_tokens),0) from agent_model_calls where run_id=?",
                Long.class, run.getId());
    }
}
