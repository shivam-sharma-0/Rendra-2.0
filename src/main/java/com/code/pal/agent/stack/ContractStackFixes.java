package com.code.pal.agent.stack;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.contract.ContractFixes;
import com.code.pal.agent.contract.DeterministicFixers;
import com.code.pal.agent.contract.FixAttempts;
import com.code.pal.agent.contract.ContractIdentity;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.exception.WorkflowStageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Executes S6.2 deterministic fixes before a model repair round: classifies the failure into the allowlisted
 * defects, claims a bounded attempt, refreshes npm locks only on a disposable candidate and applies every
 * replacement in one version-checked staged batch. Infrastructure failures fall through to the normal path.
 */
@Slf4j
@Component
public class ContractStackFixes implements ContractFixes {
    private static final Pattern VERSION_UNAVAILABLE = Pattern.compile("(?i)ETARGET|No matching version found|notarget");
    private static final Pattern LOCK_MISMATCH = Pattern.compile(
            "(?i)not in sync|from lock file|Invalid: lock file|can only install packages when your package\\.json and");
    private static final List<String> SNAPSHOT = List.of("index.html", "src/main.tsx", "package.json", "package-lock.json",
            "pnpm-lock.yaml", "yarn.lock");
    private static final int MAX_FILE_BYTES = 1_000_000;

    private final ChangeSetService changes;
    private final ProjectFiles files;
    private final DependencyService dependencies;
    private final ProjectLeaseService leases;
    private final SandboxPort sandbox;
    private final ObjectMapper mapper;

    public ContractStackFixes(ChangeSetService changes, ProjectFiles files, DependencyService dependencies,
            ProjectLeaseService leases, SandboxPort sandbox, ObjectMapper mapper) {
        this.changes = changes;
        this.files = files;
        this.dependencies = dependencies;
        this.leases = leases;
        this.sandbox = sandbox;
        this.mapper = mapper;
    }

    @Override
    public boolean tryFix(AgentContext ctx, AgentRun run, BuildContract contract, ValidationReport failure) {
        if (contract.schemaVersion() != 2) {
            return false;
        }
        return tryFix(ctx, run, contract, ContractIdentity.hash(contract, mapper), failure, () -> {});
    }

    /** Production tool-loop adapter; it does not enable contract planning or execute generated commands. */
    public boolean tryFix(AgentContext ctx, AgentRun run, ValidationReport failure, Runnable beforeIo) {
        if (!files.regularFile(ctx.projectId(), ctx.root(), "package.json")) {
            return false;
        }
        JsonNode manifest;
        try {
            manifest = mapper.readTree(new String(files.readBytes(ctx.projectId(), ctx.root(), "package.json", 256_000),
                    StandardCharsets.UTF_8));
        } catch (JacksonException invalid) {
            return false;
        }
        if (!manifest.path("dependencies").has("vite") && !manifest.path("devDependencies").has("vite")) {
            return false;
        }
        String identity = ContractIdentity.hashValue(Map.of("route", "tool-loop-vite", "starter", Starter.TEMPLATE_VERSION), mapper);
        return tryFix(ctx, run, null, identity, failure, beforeIo);
    }

    private boolean tryFix(AgentContext ctx, AgentRun run, BuildContract contract, String identity,
            ValidationReport failure, Runnable beforeIo) {
        if (run.getRequestFingerprint() == null) {
            return false;
        }
        UUID project = ctx.projectId();
        var view = changes.view(ctx);
        String stage = view.stagedRoot();
        var snapshot = new LinkedHashMap<String, String>();
        var versions = new LinkedHashMap<String, String>();
        for (String path : SNAPSHOT) {
            versions.put(path, files.version(project, stage, path));
            if (files.regularFile(project, stage, path)) {
                snapshot.put(path, new String(files.readBytes(project, stage, path, MAX_FILE_BYTES), StandardCharsets.UTF_8));
            }
        }
        var defects = defects(failure, snapshot);
        if (contract == null && !Starter.VITE_REACT.files("App").get("src/main.tsx").equals(snapshot.get("src/main.tsx"))) {
            // An arbitrary custom bootstrap may expect a different mount node; do not invent its entrypoint.
            defects.remove("PREVIEW_ENTRYPOINT_MISSING");
        }
        if (defects.isEmpty()) {
            return false;
        }
        var fixer = new DeterministicFixers(mapper);
        var fix = contract == null ? fixer.planVite(identity, snapshot, defects) : fixer.plan(contract, snapshot, defects);
        if (fix.replacements().isEmpty() && !fix.refreshLockfiles()) {
            return false;
        }
        beforeIo.run();
        int revision = view.revision();
        Runnable requireCurrent = () -> {
            if (changes.view(ctx).revision() != revision) {
                throw new WorkflowStageException("Staged changes moved before the fix was applied");
            }
        };
        if (!new FixAttempts(files, leases, mapper).claim(run, identity, fix, requireCurrent)) {
            return false;
        }
        var writes = new LinkedHashMap<>(fix.replacements());
        if (fix.refreshLockfiles()) {
            String candidate = changes.createValidationCandidate(ctx, "fix");
            try {
                fix.replacements().forEach((path, content) -> files.writeText(project, candidate, path, content));
                writes.putAll(dependencies.refreshNpmLocks(project, candidate));
            } catch (RuntimeException infrastructure) {
                log.warn("contract_fix_lock_refresh_failed runId={} reason={}", run.getId(),
                        infrastructure.getClass().getSimpleName());
                return false;
            } finally {
                sandbox.removeTree(project, candidate);
            }
        }
        var batch = new ArrayList<ChangeSetService.Replacement>();
        writes.forEach((path, content) -> batch.add(new ChangeSetService.Replacement(path,
                versions.containsKey(path) ? versions.get(path) : files.version(project, stage, path), content)));
        requireCurrent.run();
        changes.replaceBatch(ctx, batch);
        log.info("contract_fix_applied runId={} defects={} files={}", run.getId(), defects, writes.keySet());
        return changes.view(ctx).revision() > revision;
    }

    /** Only allowlisted, evidence-backed defects; anything else goes to the model repair. */
    static Set<String> defects(ValidationReport failure, Map<String, String> snapshot) {
        var defects = new HashSet<String>();
        if (!snapshot.containsKey("index.html") && snapshot.containsKey("src/main.tsx")) {
            defects.add("PREVIEW_ENTRYPOINT_MISSING");
        }
        for (ProjectDiagnostic diagnostic : failure.introducedDiagnostics()) {
            String message = diagnostic.message() == null ? "" : diagnostic.message();
            if (!"DEPENDENCY_INSTALL_FAILED".equals(diagnostic.code())) {
                continue;
            }
            if (VERSION_UNAVAILABLE.matcher(message).find()) {
                defects.add("DEPENDENCY_VERSION_UNAVAILABLE");
            }
            if (LOCK_MISMATCH.matcher(message).find()) {
                defects.add("LOCKFILE_MISMATCH");
            }
        }
        return defects;
    }
}
