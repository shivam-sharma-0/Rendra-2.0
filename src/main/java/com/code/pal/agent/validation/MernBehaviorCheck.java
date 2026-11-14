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
        // An edit of a saved app has no acceptance of its own: it must still pass the saved app's API checks.
        if (recipe == null)
            recipe = criteria.regression(ctx);
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

    public record ApiView(boolean reached, Integer status, String body, String problem) {
    }

    public ApiView request(AgentContext ctx, String method, String path, String body, boolean signedIn) {
        String root = ".codepal/api-check/" + ctx.runId() + "/" + UUID.randomUUID();
        String name = "api-" + UUID.randomUUID().toString().replace("-", "");
        String candidate = null;
        GeneratedDatabaseService.ValidationDatabase db = null;
        try {
            candidate = changes.createValidationCandidate(ctx, "api-check");
            String failure = servers.build(ctx.projectId(), candidate, root);
            if (failure != null)
                return new ApiView(false, null, null, "The server did not build: " + failure);
            db = databases.openValidation(ctx.projectId(), ctx.runId(), sandbox.databaseAddress());
            var lease = db;
            sandbox.startServer(ctx.projectId(), name, root, List.of("node", servers.entry(ctx.projectId(), root)),
                    Map.of("MONGODB_URI", lease.uri(), "NODE_ENV", "test"), 3000);
            return ApiRequestProbe.send(() -> sandbox.server(ctx.projectId(), name), method, path, body, signedIn);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailable) {
            return new ApiView(false, null, null, "The API check could not run: " + unavailable.getClass().getSimpleName());
        } finally {
            try {
                sandbox.stopServer(ctx.projectId(), name);
                databases.closeValidation(db);
                if (candidate != null)
                    sandbox.removeTree(ctx.projectId(), candidate);
                sandbox.removeTree(ctx.projectId(), root);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class CandidateBuildFailed extends RuntimeException {
    }
}
