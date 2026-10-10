package com.code.pal.agent.config;

import com.code.pal.project.domain.ProjectType;
import com.code.pal.shared.config.KeyVault;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Configuration for agent execution and its provider connection. */
@Slf4j
@Component
@ConfigurationProperties(prefix = "app.agent")
@Getter
@Setter
public class AgentProperties implements org.springframework.context.EnvironmentAware {

    /**
     * Where the master key is read from: the OS environment and the gitignored secrets file, so the keys
     * work however the backend is started (an IDE that predates the environment variable included).
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    @lombok.Setter(lombok.AccessLevel.NONE)
    private org.springframework.core.env.Environment environment;

    @Override
    public void setEnvironment(org.springframework.core.env.Environment environment) {
        this.environment = environment;
    }

    private final Llm llm = new Llm();
    private final Models models = new Models();
    private final Sandbox sandbox = new Sandbox();
    private final Limits limits = new Limits();
    /** Provider-specific endpoints. The legacy {@code llm} block remains the compatibility fallback. */
    private Map<String, Provider> providers = new LinkedHashMap<>();
    private BillingMode billingMode = BillingMode.PAID;
    /** Routing state, not configuration: models that recently failed, with their cooldown end (nanoTime). */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, Long> unavailableUntil = new ConcurrentHashMap<>();
    /** Upper bound on candidates inspected per routing decision. */
    private static final int MAX_ROUTING_PROBES = 16;

    public enum BillingMode { FREE, PAID }

    /** Root directory holding one sub-folder per project's code. */
    private String workspaceBasePath = "./workspaces";
    private int maxIterations = 40;
    /** Hard caps independent of iterations because one response may request several tools. */
    private int maxModelCalls = 40;
    private int maxToolCalls = 100;
    /** Approximate character budget for prompt context plus accumulated tool results. */
    private int contextBudgetChars = 80_000;
    /**
     * Before each model call, tool results and written file contents older than the last
     * {@code compactKeepTurns} model turns are replaced by short stubs (see ConversationCompactor).
     */
    private boolean compactContext = true;
    private int compactKeepTurns = 2;
    /** Parts smaller than this are left as they are. */
    private int compactMinChars = 800;
    private int projectMapBudgetChars = 18_000;
    private int conversationBudgetChars = 20_000;
    /** Earlier requests left out of the conversation budget, one line each (0 = off). */
    private int conversationDigestChars = 1_500;
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
    /**
     * A run still QUEUED and untouched after this long is requeued (its enqueue was probably lost).
     * A run legitimately waiting behind a busy project is merely parked again, so this can be short.
     */
    private long queuedRecoveryMs = 60_000;
    /** A live worker renews its run's heartbeat + project lock at this interval. */
    private long heartbeatIntervalMs = 15_000;
    /** After a build first validates, one more turn reviews it against the request and a quality checklist. */
    private boolean polishTurn = true;
    /** Restore the pre-commit archive when a worker dies while committing a BUILD run. */
    private boolean rollbackOnFailure = true;
    /** Maximum fix/validate cycles after the first failed validation. */
    private int maxRepairAttempts = 3;
    /** Bound structured diagnostics returned to the model. */
    private int maxValidationDiagnostics = 40;

    /**
     * How long a project lock / run heartbeat stays valid without renewal. A running worker renews
     * every {@link #heartbeatIntervalMs}, so a run is only reclaimed once several heartbeats are
     * missed (i.e. the worker actually died) â€” never while it is merely busy in a long LLM stream.
     */
    public long leaseMs() {
        return Math.max(30_000, heartbeatIntervalMs * 3);
    }

    @PostConstruct
    void decryptKeys() {
        String masterKey = environment != null ? environment.getProperty("GATEWAY_MASTER_KEY") : System.getenv("GATEWAY_MASTER_KEY");
        if (masterKey == null || masterKey.isBlank()) {
            int cleared = 0;
            for (Provider p : providers.values()) {
                if (KeyVault.isEncrypted(p.getApiKey())) { p.setApiKey(""); cleared++; }
                if (p.getApiKeys().stream().anyMatch(KeyVault::isEncrypted)) {
                    p.setApiKeys(p.getApiKeys().stream()
                            .map(k -> KeyVault.isEncrypted(k) ? "" : k)
                            .filter(k -> !k.isBlank()).toList());
                    cleared++;
                }
            }
            if (cleared > 0) {
                log.error("GATEWAY_MASTER_KEY is not set — {} provider(s) with ENC() keys are disabled. "
                        + "Set the env var and restart.", cleared);
            }
            sanitizeModelLists();
            reportRouting();
            return;
        }
        int count = 0;
        if (KeyVault.isEncrypted(llm.getApiKey())) {
            llm.setApiKey(KeyVault.decryptIfEncrypted(llm.getApiKey(), masterKey));
            count++;
        }
        for (Provider p : providers.values()) {
            if (KeyVault.isEncrypted(p.getApiKey())) {
                p.setApiKey(KeyVault.decryptIfEncrypted(p.getApiKey(), masterKey));
                count++;
            }
            if (!p.getApiKeys().isEmpty()) {
                List<String> decrypted = new ArrayList<>();
                for (String k : p.getApiKeys()) {
                    if (KeyVault.isEncrypted(k)) {
                        decrypted.add(KeyVault.decryptIfEncrypted(k, masterKey));
                        count++;
                    } else {
                        decrypted.add(k);
                    }
                }
                p.setApiKeys(decrypted);
            }
        }
        if (count > 0) log.info("Decrypted {} API key(s)", count);
        sanitizeModelLists();
        reportRouting();
    }

    private void sanitizeModelLists() {
        models.setFreeBuild(filterUsable(models.getFreeBuild()));
        models.setFreeChat(filterUsable(models.getFreeChat()));
        models.setFreePlan(filterUsable(models.getFreePlan()));
        models.setBuild(filterUsable(models.getBuild()));
        models.setChat(filterUsable(models.getChat()));
        models.setPlan(filterUsable(models.getPlan()));
        models.setVision(filterUsable(models.getVision()));
    }

    private List<String> filterUsable(List<String> modelList) {
        if (modelList == null || modelList.isEmpty()) return modelList;
        List<String> filtered = modelList.stream().filter(m -> {
            int sep = m.indexOf(':');
            if (sep <= 0) return true;
            String provider = m.substring(0, sep).toLowerCase(Locale.ROOT);
            Provider p = providers.get(provider);
            return p != null && !p.resolvedKeys().isEmpty();
        }).toList();
        int removed = modelList.size() - filtered.size();
        if (removed > 0) log.warn("Removed {} model(s) with unconfigured providers from routing", removed);
        return filtered;
    }

    /**
     * The modes (for the active billing mode) and vision with no usable model left after unconfigured
     * providers were removed; such a mode cannot run. Logged as an error at startup.
     */
    public List<String> modesWithoutModels() {
        boolean free = billingMode == BillingMode.FREE;
        Map<String, List<String>> lists = new LinkedHashMap<>();
        lists.put("BUILD", free ? models.getFreeBuild() : models.getBuild());
        lists.put("CHAT", free ? models.getFreeChat() : models.getChat());
        lists.put("PLAN", free ? models.getFreePlan() : models.getPlan());
        lists.put("VISION", models.getVision());
        return lists.entrySet().stream()
                .filter(entry -> entry.getValue() == null || entry.getValue().stream().noneMatch(m -> m != null && !m.isBlank()))
                .map(Map.Entry::getKey).toList();
    }

    private void reportRouting() {
        List<String> missing = modesWithoutModels();
        if (!missing.isEmpty()) {
            log.error("No usable model for {} (billing mode {}): check agent.models and the providers' API keys; "
                    + "requests in that mode will fail until one is configured", missing, billingMode);
        }
    }

    @Getter
    @Setter
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private double temperature = 0.2;
        /** Per-provider HTTP timeout; keep this shorter than the overall agent run timeout. */
        private long requestTimeoutMs = 60_000;
        /**
         * A streaming response that sends no bytes for this long is treated as a dead connection. The
         * request timeout only covers the wait for response headers, never the streamed body.
         */
        private long streamIdleTimeoutMs = 60_000;
        /**
         * Wall-clock budget for one agent model call, retries included, so one failing provider cannot
         * consume the whole run; the runner then falls back to the next configured model.
         */
        private long callBudgetMs = 90_000;
        /** A model whose call failed is skipped by routing for this long while others are available. */
        private long providerCooldownMs = 60_000;
        /**
         * A model the account cannot use (HTTP 403 / 404 / 410: e.g. not served on the free tier, or unknown)
         * is skipped this long, so runs do not keep trying it.
         */
        private long modelUnavailableCooldownMs = 1_800_000;
        /** An API key the provider rejected (HTTP 401) is skipped this long; the provider's other keys go on. */
        private long rejectedKeyCooldownMs = 1_800_000;
        /** Initial request plus this many retry attempts are bounded by maxAttempts. */
        private int maxAttempts = 3;
        private long retryInitialBackoffMs = 500;
        private long retryMaxBackoffMs = 5_000;
        /**
         * Output cap ({@code max_tokens}) for agent and requirements calls; it bounds what one call can cost,
         * which the daily quota reserves before the call. Reasoning models count their reasoning in it.
         */
        private int maxOutputTokens = 8_192;
        /** Output cap for a small-talk reply. */
        private int smallTalkMaxOutputTokens = 1_024;
        /** Output cap for reading attached images (the vision models reason before they answer). */
        private int visionMaxOutputTokens = 4_096;
        /** Output cap for the one-word intent answer. */
        private int intentMaxOutputTokens = 32;
    }

    /** Per-user guards that stop one account from exhausting the shared (often free-tier) LLM quota. */
    @Getter
    @Setter
    public static class Limits {
        /** Agent turns a user may start per minute; 0 disables the check. */
        private int turnsPerMinute = 10;
        /** Runs a user may have queued or running at once; 0 disables the check. */
        private int maxActiveRunsPerUser = 2;
        /** Prompt + completion tokens a user may consume per UTC day; 0 disables the check. */
        private long dailyTokenQuota = 2_000_000;
    }

    @Getter
    @Setter
    public static class Provider {
        private String baseUrl;
        private String apiKey;
        private List<String> apiKeys = new ArrayList<>();
        /**
         * Sent as reasoning_effort (e.g. "low") when set: thinking models spend far less time per call, which
         * matters in an agent loop of many short calls.
         */
        private String reasoningEffort;

        public List<String> resolvedKeys() {
            if (!apiKeys.isEmpty()) {
                return apiKeys.stream().filter(k -> k != null && !k.isBlank()).toList();
            }
            if (apiKey != null && !apiKey.isBlank()) return List.of(apiKey);
            return List.of();
        }
    }

    @Getter
    @Setter
    public static class Sandbox {
        /** Production runs require Docker; tests and legacy embedders may leave this false. */
        private boolean required = false;
        /**
         * Without the sandbox, validation runs generated code (npm scripts, build configs) directly on
         * the backend host. The application refuses to start that way unless this is set explicitly.
         */
        private boolean allowHostExecution = false;
        /** Docker CLI used by the backend process. In WSL this is normally simply {@code docker}. */
        private String dockerCommand = "docker";
        /** Optional whitespace-separated arguments, e.g. {@code -e docker} when launched from Windows. */
        private String dockerCommandArgs = "";
        private String image = "node:22-bookworm-slim";
        /**
         * Docker network for project containers. "none" keeps generated code off the network while
         * it is validated; an existing container on another network is recreated (its volume stays).
         */
        private String network = "none";
        private String containerPrefix = "codepal-sandbox-";
        /**
         * The project base directory as the Docker daemon sees it, bind-mounted into each container.
         * Blank derives it from {@code workspace-base-path}: the Linux path inside a
         * {@code \\wsl.localhost\<distro>\...} share, or that path itself when the backend runs on Linux.
         */
        private String hostWorkspacePath = "";
        /** uid:gid the container runs as; must own the project folders the backend writes. */
        private String user = "1000:1000";
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
        /**
         * Network for the short-lived dependency-install container, the only sandbox command that may
         * reach package registries. Installs run with lifecycle scripts disabled.
         */
        private String installNetwork = "bridge";
        private long installTimeoutMs = 300_000;
        /** Maximum size of one installed dependency set (node_modules). */
        private long maxDependencyBytes = 1_000_000_000;
        /**
         * Load the staged site in a headless browser before committing, and report runtime errors
         * (exceptions, console errors, missing assets, blank page) to the agent.
         */
        private boolean runtimeCheck = true;
        /** Image repository for the browser probe; the tag is derived from the probe's content. */
        private String browserImage = "codepal-browser";
        private long browserTimeoutMs = 60_000;
        /**
         * While a BUILD run works, build its staged changes into a draft preview once the agent has
         * stopped writing for this long; 0 turns drafts off.
         */
        private long draftPreviewQuietMs = 3_000;
        /**
         * App servers (a MERN project's Express API) run on this internal Docker network: no route out,
         * reachable only through the gateway.
         */
        private String appsNetwork = "codepal-apps";
        /** The gateway listens on this loopback port of the Docker host; the backend proxies through it. */
        private int gatewayPort = 55400;
        /** Host the backend uses to reach the gateway (loopback: same machine, or WSL's mirrored network). */
        private String gatewayHost = "127.0.0.1";
        /**
         * App servers get their own MongoDB, a container on the apps network with authentication on, never
         * the platform's database. The backend reaches it through the gateway on this loopback port.
         */
        private String appsDatabaseImage = "mongo:7";
        private int appsDatabasePort = 55417;
        private int appsDatabaseMemoryMb = 384;
        /** Root password of the apps' MongoDB; empty: generated once and kept in the workspace base. */
        private String appsDatabasePassword = "";
        /** Without Docker (local development only): the MongoDB app servers and the backend use directly. */
        private String localAppsDatabaseUri = "mongodb://127.0.0.1:27017/?directConnection=true";
        private String gatewayImage = "codepal-gateway";
        private int serverMemoryMb = 256;
        /** An app server nobody has called for this long is stopped; the next request starts it again. */
        private long serverIdleStopMs = 900_000;
        /** Building the browser image downloads Chromium once. */
        private long browserImageBuildTimeoutMs = 900_000;
    }

    @Getter
    @Setter
    public static class Models {
        /** Free-tier models often rate-limit individually; round-robin spreads calls across all of them. */
        private List<String> build = new ArrayList<>();
        private List<String> chat = new ArrayList<>();
        private List<String> plan = new ArrayList<>();

        private List<String> freeBuild = new ArrayList<>();
        private List<String> freeChat = new ArrayList<>();
        private List<String> freePlan = new ArrayList<>();
        /** Image understanding, tried in order (the first that answers wins): not round-robin. */
        private List<String> vision = new ArrayList<>();

        private final AtomicInteger buildIndex = new AtomicInteger();
        private final AtomicInteger chatIndex = new AtomicInteger();
        private final AtomicInteger planIndex = new AtomicInteger();
        private final AtomicInteger freeBuildIndex = new AtomicInteger();
        private final AtomicInteger freeChatIndex = new AtomicInteger();
        private final AtomicInteger freePlanIndex = new AtomicInteger();

        String nextBuild() { return roundRobin(build, buildIndex); }
        String nextChat() { return roundRobin(chat, chatIndex); }
        String nextPlan() { return roundRobin(plan, planIndex); }
        String nextFreeBuild() { return roundRobin(freeBuild, freeBuildIndex); }
        String nextFreeChat() { return roundRobin(freeChat, freeChatIndex); }
        String nextFreePlan() { return roundRobin(freePlan, freePlanIndex); }

        private static String roundRobin(List<String> options, AtomicInteger index) {
            if (options == null || options.isEmpty()) return null;
            return options.get(Math.floorMod(index.getAndIncrement(), options.size()));
        }
    }

    /**
     * Next model for {@code mode} in round-robin order, skipping models that failed recently (see
     * {@link #markModelUnavailable}). If every configured model is cooling down, the first candidate
     * is returned anyway: trying a possibly-recovered model beats failing without a call.
     */
    public ModelSelection modelFor(ProjectType mode) {
        ModelSelection first = null;
        // A model may be listed twice (two turns in the rotation), so meeting the first one again does not
        // mean the list was exhausted: probe a bounded number of entries instead.
        for (int probe = 0; probe < MAX_ROUTING_PROBES; probe++) {
            ModelSelection candidate = nextModelFor(mode);
            if (first == null) first = candidate;
            if (!coolingDown(candidate)) return candidate;
        }
        return first;
    }

    /**
     * The model a run sticks to: the first one listed for {@code mode} that is not cooling down (lists are
     * in priority order), else the first listed. One model per run keeps one line of reasoning and one
     * code style; {@link #modelFor} picks another only while this one is failing.
     */
    public ModelSelection preferredModel(ProjectType mode) {
        List<String> configured = switch (mode) {
            case CHAT -> billingMode == BillingMode.FREE ? models.getFreeChat() : models.getChat();
            case PLAN -> billingMode == BillingMode.FREE ? models.getFreePlan() : models.getPlan();
            case BUILD -> billingMode == BillingMode.FREE ? models.getFreeBuild() : models.getBuild();
        };
        List<ModelSelection> all = configured == null ? List.of()
                : configured.stream().filter(entry -> entry != null && !entry.isBlank()).map(this::selection).toList();
        if (all.isEmpty()) return modelFor(mode);
        return all.stream().filter(this::available).findFirst().orElse(all.getFirst());
    }

    /** False while the model is cooling down after a failure. */
    public boolean available(ModelSelection selection) {
        return !coolingDown(selection);
    }

    /** A provider/model that just failed is skipped for {@code llm.providerCooldownMs}. */
    public void markModelUnavailable(ModelSelection selection) {
        markModelUnavailable(selection, llm.getProviderCooldownMs());
    }

    /** As {@link #markModelUnavailable(ModelSelection)}, for a failure that says how long it lasts. */
    public void markModelUnavailable(ModelSelection selection, long cooldownMs) {
        long cooldown = Math.max(0, cooldownMs);
        if (selection == null || cooldown == 0) return;
        unavailableUntil.put(routingKey(selection), System.nanoTime() + cooldown * 1_000_000);
    }

    public void markModelAvailable(ModelSelection selection) {
        if (selection != null) unavailableUntil.remove(routingKey(selection));
    }

    private boolean coolingDown(ModelSelection selection) {
        Long until = unavailableUntil.get(routingKey(selection));
        if (until == null) return false;
        if (System.nanoTime() - until < 0) return true;
        unavailableUntil.remove(routingKey(selection), until);
        return false;
    }

    private static String routingKey(ModelSelection selection) {
        return selection.provider() + ":" + selection.model();
    }

    /** The vision models in preference order, skipping ones that failed recently (all of them if every one did). */
    public List<ModelSelection> visionModels() {
        List<ModelSelection> all = models.getVision() == null ? List.of()
                : models.getVision().stream().filter(entry -> entry != null && !entry.isBlank()).map(this::selection).toList();
        List<ModelSelection> available = all.stream().filter(selection -> !coolingDown(selection)).toList();
        return available.isEmpty() ? all : available;
    }

    private ModelSelection nextModelFor(ProjectType mode) {
        String configured = switch (mode) {
            case CHAT -> billingMode == BillingMode.FREE ? models.nextFreeChat() : models.nextChat();
            case PLAN -> billingMode == BillingMode.FREE ? models.nextFreePlan() : models.nextPlan();
            case BUILD -> billingMode == BillingMode.FREE ? models.nextFreeBuild() : models.nextBuild();
        };
        return selection(configured);
    }

    private ModelSelection selection(String configured) {
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
        List<String> priority = billingMode == BillingMode.FREE
                ? List.of("mistral", "nvidia", "openrouter")
                : List.of("mistral", "nvidia", "openrouter");
        for (String name : priority) {
            Provider p = providers.get(name);
            if (p != null && !p.resolvedKeys().isEmpty()) return name;
        }
        return priority.getFirst();
    }

    public record ModelSelection(String provider, String model) {}
}
