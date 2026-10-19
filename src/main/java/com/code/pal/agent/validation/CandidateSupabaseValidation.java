package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.lease.TrustedOperationPendingException;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SupabaseScratch;
import com.code.pal.agent.workspace.SupabaseScratchException;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Owns one scratch target across independent API evidence and the same candidate's browser builds. */
@Service
public final class CandidateSupabaseValidation {
    public record Result<T>(T value, List<ValidationCheck> checks, boolean applicable) {
        public Result { checks = List.copyOf(checks); }
    }

    private final BuildDecisions decisions;
    private final ApprovedProjectMemory memory;
    private final ProjectFiles files;
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final ObjectProvider<SupabaseScratch> scratch;
    private final SupabaseApiAcceptance acceptance;

    public CandidateSupabaseValidation(BuildDecisions decisions, ApprovedProjectMemory memory, ProjectFiles files,
            AgentProperties properties, ObjectMapper mapper, ObjectProvider<SupabaseScratch> scratch, SupabaseApiAcceptance acceptance) {
        this.decisions = decisions;
        this.memory = memory;
        this.files = files;
        this.properties = properties;
        this.mapper = mapper;
        this.scratch = scratch;
        this.acceptance = acceptance;
    }

    /** The callback receives only an isolated target, never stored connector values or migration authority. */
    public <T> Result<T> evaluate(AgentContext context, String candidate, Function<SupabaseScratch.Session, T> browser) {
        var state = decisions.state(context.projectId());
        if (state.blocked()) return new Result<>(null, List.of(skipped("CAPABILITY_APPROVAL",
                "Approved storage or accounts changed; confirm requirements again")), true);
        if (state.choice() == null || state.choice().storage() != BuildDecisions.Storage.SUPABASE) {
            return new Result<>(browser.apply(null), List.of(), false);
        }
        if (!properties.getSupabaseValidation().isEnabled()) {
            return unavailable("Isolated Supabase validation is disabled; cloud behavior has not been certified");
        }
        var factory = scratch.getIfAvailable();
        if (factory == null) return unavailable("Isolated Supabase validation requires the configured Docker sandbox");
        var approved = memory.current(context.projectId());
        if (approved == null || approved.specJson() == null) return unavailable("Current approved cloud data scope is unavailable");
        boolean accounts = state.choice().auth() == BuildDecisions.Auth.ACCOUNTS;
        List<ApprovedSpec.Entity> entities;
        try {
            entities = SupabaseValidationScope.entities(mapper.readTree(approved.specJson()), accounts);
        } catch (RuntimeException unsupported) {
            return unavailable("Approved cloud data scope is stale or unsupported; refresh the approved schema mapping and checks");
        }
        if (entities.isEmpty() && !accounts) return unavailable("Approved cloud data entities are unavailable; CRUD coverage cannot be certified");
        List<SupabaseScratch.Migration> migrations;
        try {
            migrations = migrations(context, candidate);
        } catch (CancellationException | LlmCancelledException | LeaseLostException | TrustedOperationPendingException cancelled) {
            throw cancelled;
        } catch (RuntimeException unreadable) {
            return unavailable("The complete staged migration history could not be read within validation limits");
        }
        if (!entities.isEmpty() && migrations.isEmpty()) {
            return new Result<>(null, List.of(new ValidationCheck("SUPABASE_SCHEMA", ValidationCheck.Outcome.FAIL, true,
                    "Approved cloud entities require schema migrations in supabase/migrations")), true);
        }
        var checks = new ArrayList<ValidationCheck>();
        SupabaseScratch.Session session;
        try {
            session = factory.open(context.projectId(), context.runId(), migrations);
        } catch (SupabaseScratchException failed) {
            return new Result<>(null, List.of(new ValidationCheck("SUPABASE_SCHEMA",
                    failed.schemaFailure() ? ValidationCheck.Outcome.FAIL : ValidationCheck.Outcome.SKIPPED, true, failed.getMessage())), true);
        }
        T value;
        RuntimeException primary = null;
        try {
            checks.add(new ValidationCheck("SUPABASE_SCHEMA", ValidationCheck.Outcome.PASS, true,
                    "Complete staged migrations applied to a fresh restricted-role database"));
            checks.addAll(acceptance.check(session, entities, accounts));
            value = browser.apply(session);
            if (!approved.equals(memory.current(context.projectId())) || !state.equals(decisions.state(context.projectId()))) {
                checks.add(skipped("SUPABASE_APPROVAL", "Approved requirements or cloud capabilities changed during validation"));
            }
        } catch (RuntimeException failed) {
            primary = failed;
            throw failed;
        } finally {
            try { session.close(); }
            catch (RuntimeException cleanup) {
                checks.add(skipped("SUPABASE_CLEANUP", "Isolated Supabase resource cleanup needs verification; resources retained"));
                if (primary != null) primary.addSuppressed(new SupabaseScratchException("Isolated Supabase cleanup needs verification", false));
            }
        }
        return new Result<>(value, checks, true);
    }

    public List<ValidationCheck> browserDisabledChecks(AgentContext context) {
        var choice = decisions.state(context.projectId()).choice();
        return choice != null && choice.storage() == BuildDecisions.Storage.SUPABASE
                ? List.of(skipped("SUPABASE_BEHAVIOUR", "Cloud acceptance requires isolated runtime validation; browser validation is disabled"))
                : List.of();
    }

    /** Cached browser/API receipts cannot outlive a changed approved schema, capability or rollout choice. */
    public String bindingKey(AgentContext context) {
        var state = decisions.state(context.projectId());
        if (state.choice() == null || state.choice().storage() != BuildDecisions.Storage.SUPABASE) return "";
        var approved = memory.current(context.projectId());
        return state + "|enabled=" + properties.getSupabaseValidation().isEnabled() + "|approval="
                + (approved == null ? "absent" : approved.projectionId() + ":" + approved.revision() + ":"
                        + ContractStore.hash(approved.specJson() == null ? "" : approved.specJson()));
    }

    private List<SupabaseScratch.Migration> migrations(AgentContext context, String candidate) {
        String root = ProjectPaths.combine(candidate, "supabase/migrations");
        var settings = properties.getSupabaseValidation();
        var entries = files.list(context.projectId(), root, 2);
        if (entries.size() > settings.getMaxMigrations() || entries.stream().anyMatch(ProjectFiles.Entry::directory)) {
            throw new IllegalArgumentException("Migration history exceeds validation scope");
        }
        var migrations = new ArrayList<SupabaseScratch.Migration>();
        long remaining = settings.getMaxMigrationBytes();
        for (var entry : entries.stream().sorted(Comparator.comparing(ProjectFiles.Entry::path)).toList()) {
            if (!entry.path().endsWith(".sql")) continue;
            if (!entry.path().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,195}\\.sql") || entry.path().contains("..")
                    || entry.size() < 1 || entry.size() > remaining) {
                throw new IllegalArgumentException("Migration history is invalid or too large");
            }
            byte[] bytes = files.readBytes(context.projectId(), root, entry.path(), (int) Math.min(Integer.MAX_VALUE, remaining));
            remaining -= bytes.length;
            try {
                String sql = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (sql.isBlank()) throw new IllegalArgumentException("Empty migration");
                migrations.add(new SupabaseScratch.Migration(entry.path(), sql));
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException("Invalid migration encoding");
            }
        }
        return List.copyOf(migrations);
    }

    private static <T> Result<T> unavailable(String reason) {
        return new Result<>(null, List.of(skipped("SUPABASE_BEHAVIOUR", reason)), true);
    }

    private static ValidationCheck skipped(String id, String reason) {
        return new ValidationCheck(id, ValidationCheck.Outcome.SKIPPED, true, reason);
    }
}
