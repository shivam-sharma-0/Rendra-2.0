package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.RunBehaviorCriteria;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.ApprovedSpecs;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Approval binding, immutable recipe installation and resume guidance, independent of runner/model routing. */
public final class ApprovedBehaviourSetup {
    public record Unverified(ApprovedSpec.Storage storage, ApprovedSpec.Auth auth, ApprovedSpec.Readiness readiness) {}
    public record Setup(List<String> guidance, Unverified unverified) {
        public Setup { guidance = List.copyOf(guidance); }
        static Setup empty() { return new Setup(List.of(), null); }
    }

    private final ApprovedSpecs specs;
    private final RunBehaviorCriteria criteria;
    private final AgentProperties properties;

    public ApprovedBehaviourSetup(ApprovedSpecs specs, RunBehaviorCriteria criteria, AgentProperties properties) {
        this.specs = specs;
        this.criteria = criteria;
        this.properties = properties;
    }

    public Setup prepare(AgentContext context, BooleanSupplier emptyApp) {
        if (context.mode() != ProjectType.BUILD || !properties.getSandbox().isRuntimeCheck()) return Setup.empty();
        var installed = criteria.current(context);
        if (installed == null && !emptyApp.getAsBoolean()) return Setup.empty();
        var approved = specs.current(context.projectId());
        if (installed != null) {
            var recipe = approved.filter(this::browserVerifiable).flatMap(this::browserRecipe)
                    .filter(compiled -> installed.equals(compiled.criteria()))
                    .orElseThrow(() -> new WorkflowStageException("Required acceptance changed during this run; start a new run"));
            return new Setup(List.of(ToolLoopAcceptance.promptSection(recipe)), null);
        }
        if (approved.isEmpty()) return Setup.empty();
        var spec = approved.get();
        if (!browserVerifiable(spec)) {
            return new Setup(List.of(), new Unverified(spec.desired().storage(), spec.desired().auth(), spec.readiness()));
        }
        var compiled = browserRecipe(spec);
        if (compiled.isEmpty()) return Setup.empty();
        criteria.install(context.runId(), context.projectId(), spec.requirementsProjection(), spec.requirementsRevision(), compiled.get().criteria());
        return new Setup(List.of(ToolLoopAcceptance.promptSection(compiled.get())), null);
    }

    private boolean browserVerifiable(ApprovedSpec approved) {
        return approved.readiness() == ApprovedSpec.Readiness.READY
                && (approved.desired().storage() == ApprovedSpec.Storage.SUPABASE
                        ? properties.getSupabaseValidation().isEnabled() : approved.desired().auth() == ApprovedSpec.Auth.NONE);
    }

    private Optional<ToolLoopAcceptance.Compiled> browserRecipe(ApprovedSpec approved) {
        return approved.desired().storage() == ApprovedSpec.Storage.SUPABASE
                ? ToolLoopAcceptance.browserForSupabase(approved) : ToolLoopAcceptance.from(approved);
    }
}
