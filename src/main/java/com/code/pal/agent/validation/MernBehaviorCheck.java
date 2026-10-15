package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.runtime.AppServerService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.database.GeneratedDatabaseService;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

@Service
public final class MernBehaviorCheck {
    private final RunBehaviorCriteria criteria;
    private final GeneratedDatabaseService databases;
    private final AppServerService servers;
    private final SandboxPort sandbox;
    private final ChangeSetService changes;
    private final ApiBehaviorProbe probe;

    public MernBehaviorCheck(RunBehaviorCriteria criteria, GeneratedDatabaseService databases, AppServerService servers,
            SandboxPort sandbox, ChangeSetService changes, ApiBehaviorProbe probe) {
        this.criteria = criteria;
        this.databases = databases;
        this.servers = servers;
        this.sandbox = sandbox;
        this.changes = changes;
        this.probe = probe;
    }

    public List<ValidationCheck> check(AgentContext ctx) {
        var recipe = criteria.current(ctx);
        if (recipe == null || recipe.api().isEmpty())
            return List.of(new ValidationCheck("API_BEHAVIOR", ValidationCheck.Outcome.SKIPPED, true,
                    "Version-bound API acceptance criteria are unavailable"));
        String root = ".codepal/behavior/" + ctx.runId() + "/" + UUID.randomUUID();
        String name = "check-" + UUID.randomUUID().toString().replace("-", "");
        String candidate = null;
        GeneratedDatabaseService.ValidationDatabase db = null;
        List<ValidationCheck> checks = new ArrayList<>();
        try {
            candidate = changes.createValidationCandidate(ctx, "api-behavior");
            if (servers.build(ctx.projectId(), candidate, root) != null)
                throw new CandidateBuildFailed();
            db = databases.openValidation(ctx.projectId(), ctx.runId(), sandbox.databaseAddress());
            var lease = db;
            Supplier<SandboxPort.ServerEndpoint> start = () -> {
                var running = sandbox.server(ctx.projectId(), name);
                return running != null ? running
                        : sandbox.startServer(ctx.projectId(), name, root,
                                List.of("node", servers.entry(ctx.projectId(), root)),
                                Map.of("MONGODB_URI", lease.uri(), "NODE_ENV", "test"), 3000);
            };
            checks.addAll(probe.run(recipe, start, () -> sandbox.stopServer(ctx.projectId(), name)));
        } catch (CandidateBuildFailed failed) {
            checks.add(new ValidationCheck("API_BEHAVIOR", ValidationCheck.Outcome.FAIL, true,
                    "Candidate API did not build"));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailable) {
            checks.add(new ValidationCheck("API_BEHAVIOR", ValidationCheck.Outcome.SKIPPED, true,
                    "Candidate API validation infrastructure unavailable"));
        } finally {
            boolean stopped = true;
            try {
                sandbox.stopServer(ctx.projectId(), name);
            } catch (RuntimeException failure) {
                stopped = false;
                checks.add(new ValidationCheck("API_CLEANUP", ValidationCheck.Outcome.FAIL, true,
                        "Candidate process cleanup needs verification"));
            }
            if (stopped) {
                try {
                    databases.closeValidation(db);
                } catch (RuntimeException failure) {
                    checks.add(new ValidationCheck("DB_CLEANUP", ValidationCheck.Outcome.FAIL, true,
                            "Scratch database cleanup needs verification"));
                }
                try {
                    if (candidate != null)
                        sandbox.removeTree(ctx.projectId(), candidate);
                    sandbox.removeTree(ctx.projectId(), root);
                } catch (RuntimeException failure) {
                    checks.add(new ValidationCheck("API_FILES_CLEANUP", ValidationCheck.Outcome.FAIL, true,
                            "Candidate artifact cleanup needs verification"));
                }
            } else
                checks.add(new ValidationCheck("API_RESOURCE_RETENTION", ValidationCheck.Outcome.SKIPPED, true,
                        "Scratch data and artifacts retained until process cleanup is proven"));
        }
        return List.copyOf(checks);
    }

    private static final class CandidateBuildFailed extends RuntimeException {
    }
}
