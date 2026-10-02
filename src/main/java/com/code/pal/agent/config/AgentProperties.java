package com.code.pal.agent.config;

import com.code.pal.project.domain.ProjectType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Configuration for agent execution and its provider connection. */
@Component
@ConfigurationProperties(prefix = "app.agent")
@Getter
@Setter
public class AgentProperties {

    private final Llm llm = new Llm();
    private final Models models = new Models();
    private final Sandbox sandbox = new Sandbox();
    /** Provider-specific endpoints. The legacy {@code llm} block remains the compatibility fallback. */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** Root directory holding one sub-folder per project's code. */
    private String workspaceBasePath = "./workspaces";
    private int maxIterations = 25;
    /** Hard caps independent of iterations because one response may request several tools. */
    private int maxModelCalls = 25;
    private int maxToolCalls = 60;
    /** Approximate character budget for prompt context plus accumulated tool results. */
    private int contextBudgetChars = 80_000;
    private int projectMapBudgetChars = 18_000;
    private int conversationBudgetChars = 20_000;
    private int projectScanMaxFiles = 5_000;
    private int projectScanMaxDepth = 12;
    private int projectInstructionMaxChars = 4_000;
    private long runTimeoutMs = 300_000;
    private int workerConcurrency = 2;
    /** Maximum readonly calls from one model response to run at once. */
    private int toolConcurrency = 4;
    /** Coalesce model token deltas before broadcasting a live socket delta. */
    private int streamBatchChars = 96;
    private long streamBatchIntervalMs = 250;
    /** Coarser cadence for persisting the growing assistant text, so streaming stays O(n) not O(n²). */
    private int streamPersistChars = 4_000;
    private long streamPersistIntervalMs = 3_000;
    /** How often a crashed/lost run is recovered. */
    private long staleSweepIntervalMs = 30_000;
    /** A live worker renews its run's heartbeat + project lock at this interval. */
    private long heartbeatIntervalMs = 15_000;
    /** Restore the pre-run Git snapshot when a BUILD run fails. */
    private boolean rollbackOnFailure = true;
    /** Maximum fix/validate cycles after the first failed validation. */
    private int maxRepairAttempts = 3;
    /** Per controlled package validation process. */
    private long validationTimeoutMs = 120_000;
    /** Bound structured diagnostics returned to the model. */
    private int maxValidationDiagnostics = 40;
    /** Bound captured process output before parsing, independent of model context limits. */
    private int validationOutputMaxChars = 80_000;

    /**
     * How long a project lock / run heartbeat stays valid without renewal. A running worker renews
     * every {@link #heartbeatIntervalMs}, so a run is only reclaimed once several heartbeats are
     * missed (i.e. the worker actually died) â€” never while it is merely busy in a long LLM stream.
     */
    public long leaseMs() {
        return Math.max(30_000, heartbeatIntervalMs * 3);
    }

    @Getter
    @Setter
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private double temperature = 0.2;
        /** Per-provider HTTP timeout; keep this shorter than the overall agent run timeout. */
        private long requestTimeoutMs = 60_000;
        /** Initial request plus this many retry attempts are bounded by maxAttempts. */
        private int maxAttempts = 3;
        private long retryInitialBackoffMs = 500;
        private long retryMaxBackoffMs = 5_000;
    }

    @Getter
    @Setter
    public static class Provider {
        private String baseUrl;
        private String apiKey;
    }

    @Getter
    @Setter
    public static class Sandbox {
        /** Production runs require Docker; tests and legacy embedders may leave this false. */
        private boolean required = false;
        /** Docker CLI used by the backend process. In WSL this is normally simply {@code docker}. */
        private String dockerCommand = "docker";
        /** Optional whitespace-separated arguments, e.g. {@code -e docker} when launched from Windows. */
        private String dockerCommandArgs = "";
        private String image = "node:22-bookworm-slim";
        private String containerPrefix = "codepal-sandbox-";
        private String volumePrefix = "codepal-project-";
        private String workspacePath = "/workspace";
        private long commandTimeoutMs = 120_000;
        private int outputMaxChars = 80_000;
        private int memoryMb = 2_048;
        private double cpus = 2.0;
        /** Stop an inactive sandbox but keep its named volume. */
        private long idleStopMs = 900_000;
        /** Remove the stopped container after this period; the project volume remains intact. */
        private long idleRemoveMs = 86_400_000;
        private long cleanupIntervalMs = 60_000;
        /** Maximum combined project volume size, excluding internal .codepal snapshots. */
        private long maxProjectBytes = 500_000_000;
    }

    @Getter
    @Setter
    public static class Models {
        /** Free-tier models often rate-limit individually; round-robin spreads calls across all of them. */
        private List<String> build = new ArrayList<>();
        private List<String> chat = new ArrayList<>();
        private List<String> plan = new ArrayList<>();

        private final AtomicInteger buildIndex = new AtomicInteger();
        private final AtomicInteger chatIndex = new AtomicInteger();
        private final AtomicInteger planIndex = new AtomicInteger();

        String nextBuild() { return roundRobin(build, buildIndex); }
        String nextChat() { return roundRobin(chat, chatIndex); }
        String nextPlan() { return roundRobin(plan, planIndex); }

        private static String roundRobin(List<String> options, AtomicInteger index) {
            if (options == null || options.isEmpty()) return null;
            return options.get(Math.floorMod(index.getAndIncrement(), options.size()));
        }
    }

    public ModelSelection modelFor(ProjectType mode) {
        String configured = switch (mode) {
            case CHAT -> models.nextChat();
            case PLAN -> models.nextPlan();
            case BUILD -> models.nextBuild();
        };
        if (configured == null || configured.isBlank()) return new ModelSelection(defaultProvider(), configured);

        // Provider-qualified entries look like "nvidia:deepseek-ai/deepseek-v4.1-flash". Preserve
        // legacy model ids such as "nvidia/...:free", where the colon belongs to the model.
        int separator = configured.indexOf(':');
        if (separator > 0) {
            String candidate = configured.substring(0, separator).toLowerCase(Locale.ROOT);
            if (candidate.equals("openrouter") || candidate.equals("nvidia")
                    || providers.containsKey(candidate)) {
                return new ModelSelection(candidate, configured.substring(separator + 1));
            }
        }
        return new ModelSelection(defaultProvider(), configured);
    }

    private String defaultProvider() {
        return providers.containsKey("nvidia") ? "nvidia" : "openrouter";
    }

    public record ModelSelection(String provider, String model) {}
}
