package com.code.pal.agent.route;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.config.AgentProperties.ModelSelection;
import com.code.pal.agent.llm.LlmAccountingException;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import com.code.pal.project.domain.ProjectType;

import java.util.HashSet;
import java.util.Set;

/** Per-run route policy. Quality exclusions never cool down a provider for unrelated users. */
public final class ModelRoutes {
    private final AgentProperties properties;
    private final ProjectType mode;
    private final ModelSelection preferred;
    private final Set<ModelSelection> excluded = new HashSet<>();
    private ModelSelection current;
    private LlmException lastFailure;
    private String lastDiagnostic;
    private int repeats;
    private int failedRevisions;
    private int escalations;

    public ModelRoutes(AgentProperties properties, ProjectType mode) {
        this.properties = properties;
        this.mode = mode;
        preferred = properties.preferredModel(mode);
    }

    public ModelSelection next() {
        ModelSelection selection = properties.available(preferred) && !excluded.contains(preferred)
                ? preferred : properties.untriedModel(mode, excluded);
        if (selection == null) throw lastFailure == null ? new LlmException("No unused configured model route remains") : lastFailure;
        if (!selection.equals(current)) {
            lastDiagnostic = null;
            repeats = 0;
            failedRevisions = 0;
        }
        current = selection;
        return selection;
    }

    public LlmException lastFailure() {
        return lastFailure;
    }

    public void failed(ModelSelection selection, LlmException failure, Runnable requireActive) {
        if (failure instanceof LlmCancelledException || failure instanceof LlmQuotaExceededException
                || failure instanceof LlmAccountingException
                || failure instanceof LlmHttpException rejected && rejected.terminalRequest()) throw failure;
        requireActive.run();
        if (failure.getMessage() != null && failure.getMessage().contains("after partial output")) throw failure;
        if (failure instanceof LlmHttpException http && http.modelUnavailable()) {
            properties.markModelUnavailable(selection, properties.getLlm().getModelUnavailableCooldownMs());
        } else {
            properties.markModelUnavailable(selection);
        }
        excluded.add(selection);
        lastFailure = failure;
    }

    public boolean failedValidation(String diagnostic) {
        if (diagnostic == null || diagnostic.isBlank()) return false;
        repeats = diagnostic.equals(lastDiagnostic) ? repeats + 1 : 1;
        failedRevisions++;
        lastDiagnostic = diagnostic;
        var policy = properties.getRepair();
        boolean repeated = repeats >= Math.max(1, policy.getRepeatedFailureThreshold());
        boolean notConverging = policy.getFailedRevisionThreshold() > 0
                && failedRevisions >= policy.getFailedRevisionThreshold();
        if (current == null || (!repeated && !notConverging)
                || escalations >= Math.max(0, policy.getMaxModelEscalations())) return false;
        var candidates = new HashSet<>(excluded);
        candidates.add(current);
        var alternate = properties.untriedModel(mode, candidates);
        if (alternate == null || !properties.available(alternate)) return false;
        excluded.add(current);
        escalations++;
        repeats = 0;
        failedRevisions = 0;
        return true;
    }
}
