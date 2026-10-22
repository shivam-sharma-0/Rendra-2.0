package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.starter.Starter;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.requirement.RequirementDraft;
import com.code.pal.shared.config.KeyVault;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Explicit CLI, never a Surefire test. No Spring application, DB, generation, preview or sandbox. */
public final class PlannerBatchHarness {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProperties properties;
    private final LlmClient client;
    private final Path output;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> samples = new ArrayList<>();

    private PlannerBatchHarness(AgentProperties properties, LlmClient client, Path output) {
        this.properties = properties; this.client = client; this.output = output;
        report.put("schemaVersion", 1); report.put("correctionCalls", 0);
        report.put("scope", "requirements and contract planning only; offline validation; no generated application");
        report.put("samples", samples);
    }

    public static void main(String[] args) throws Exception {
        boolean replay = args.length == 4 && args[0].equals("--replay");
        if (args.length != 2 && !replay)
            throw new IllegalArgumentException("Expected fixture and new output directory, or --replay <batch> <fixture> <output>");
        Path output = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        Path target = Path.of("target").toAbsolutePath().normalize();
        if (!output.startsWith(target) || output.equals(target) || Files.exists(output))
            throw new IllegalArgumentException("Use a new evidence directory under target");
        if (replay) {
            Path batch = Path.of(args[1]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(batch.resolve("report.json"))) throw new IllegalArgumentException("Replay needs a batch report.json");
            // Saved outputs only: no configuration, credentials, client or model call.
            Files.createDirectories(output);
            replay(new ObjectMapper(), batch, Path.of(args[2]), output);
            return;
        }
        var properties = configuration();
        var client = new OpenAiCompatibleLlmClient(properties, new ObjectMapper());
        Files.createDirectories(output);
        new PlannerBatchHarness(properties, client, output).run(Path.of(args[0]));
    }

    static AgentProperties configuration() throws IOException {
        var environment = new StandardEnvironment();
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        environment.getPropertySources().addLast(new PropertiesPropertySource("application", Objects.requireNonNull(yaml.getObject())));
        var secrets = new Properties();
        Path source = Path.of(".secrets/secrets.properties");
        if (Files.isRegularFile(source)) try (var input = Files.newInputStream(source)) { secrets.load(input); }
        environment.getPropertySources().addLast(new PropertiesPropertySource("local-secrets", secrets));
        var properties = Binder.get(environment).bind("app.agent", AgentProperties.class).get();
        properties.setBillingMode(AgentProperties.BillingMode.FREE);
        requireFreeRoute(properties.preferredRequirementsModel());
        requireFreeRoute(properties.planningRoutes().getFirst());
        var provider = properties.getProviders().get("mistral");
        String master = environment.getProperty("GATEWAY_MASTER_KEY");
        // Only the explicitly approved free Codestral route is loaded into this unmanaged transport.
        var keys = provider.resolvedKeys().stream().map(key -> KeyVault.decryptIfEncrypted(key, master)).toList();
        if (keys.isEmpty() || keys.stream().anyMatch(String::isBlank)) throw new IllegalStateException("Mistral key unavailable");
        provider.setApiKey(keys.getFirst()); provider.setApiKeys(List.of(keys.getFirst()));
        properties.getProviders().clear(); properties.getProviders().put("mistral", provider);
        properties.getLlm().setMaxAttempts(1); properties.getPlanning().setMaxTransportAttempts(1);
        return properties;
    }

    private static void requireFreeRoute(AgentProperties.ModelSelection route) {
        if (!route.provider().equals("mistral") || !route.model().equals("codestral-latest"))
            throw new IllegalArgumentException("Batch permits only the owner's approved free mistral:codestral-latest route");
    }

    private void run(Path fixture) throws IOException {
        var cases = mapper.readTree(Files.readString(fixture)).path("cases");
        int sequence = 0;
        for (var item : cases) {
            String name = item.path("name").asString();
            String prompt = prompt(mapper, item);
            int repeats = item.path("repeats").asInt();
            if (!name.matches("[a-z-]{1,40}") || prompt.isBlank() || repeats < 1 || repeats > 10)
                throw new IllegalArgumentException("Invalid bounded synthetic case");
            for (int repeat = 1; repeat <= repeats; repeat++) {
                String id = "%02d-%s-%d".formatted(++sequence, name, repeat);
                var sample = new LinkedHashMap<String, Object>();
                sample.put("id", id); sample.put("case", name); samples.add(sample);
                sample(prompt, id, sample);
                summarize(report, samples);
                Files.writeString(output.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                System.out.println("BATCH_SAMPLE " + mapper.writeValueAsString(sample));
            }
        }
    }

    private void sample(String prompt, String id, Map<String, Object> sample) throws IOException {
        var requirementsRoute = properties.preferredRequirementsModel();
        String system = PromptLoader.load(Prompt.REQUIREMENTS_SYSTEM)
                .replace("{{runtimeCapabilities}}", PromptLoader.load(Prompt.RUNTIME_CAPABILITIES));
        var request = new LlmClient.LlmRequest(requirementsRoute.provider(), requirementsRoute.model(), List.of(
                LlmClient.LlmMessage.system(system), LlmClient.LlmMessage.user(PromptLoader.load(Prompt.REQUIREMENTS)
                        .replace("{{request}}", prompt))), List.of(), 0.0, Duration.ofMillis(properties.getRequirements().getTimeoutMs()))
                .withMaxTokens(properties.getRequirements().getMaxOutputTokens());
        var requirements = new LinkedHashMap<String, Object>(); sample.put("requirements", requirements);
        var result = invoke(request, CallPurpose.REQUIREMENTS, requirements, id + "-requirements");
        if (result == null) { sample.put("outcome", requirements.get("status")); return; }
        var input = plannerInput(mapper, UUID.randomUUID(), prompt, result, requirements);
        sample.put("contractId", input.id());
        var planner = new ContractPlanner(mapper, properties);
        LlmClient.LlmRequest prepared = prepare(planner, input, properties.planningRoutes().getFirst());
        var planning = new LinkedHashMap<String, Object>(); sample.put("planning", planning);
        var proposal = invoke(prepared, CallPurpose.PLANNER, planning, id + "-planner");
        if (proposal == null) { sample.put("outcome", planning.get("status")); return; }
        Map<String, Object> raw = inspect(planner, input, proposal, false);
        Map<String, Object> normalized = inspect(planner, input, proposal, true);
        sample.put("raw", raw); sample.put("normalized", normalized);
        sample.put("outcome", Boolean.TRUE.equals(normalized.get("valid")) ? "VALID" : "REJECTED");
    }

    static String prompt(ObjectMapper mapper, JsonNode item) throws IOException {
        return item.has("promptFixture") ? mapper.readTree(Files.readString(Path.of(item.path("promptFixture").asString())))
                .path("prompt").asString() : item.path("prompt").asString();
    }

    /** Live and replay share this planner input; only the contract id differs (fresh vs saved). */
    static ContractPlanner.Input plannerInput(ObjectMapper mapper, UUID contractId, String prompt, LlmClient.LlmResult result,
            Map<String, Object> requirements) {
        String draft = null;
        try {
            var root = mapper.readTree(ModelJson.unfenced(result.content()));
            draft = RequirementDraft.capture(root.path("spec"));
            requirements.put("pages", root.path("spec").path("pages").size());
            requirements.put("features", root.path("spec").path("features").size());
            requirements.put("questions", root.path("questions").size());
            requirements.put("draftCaptured", draft != null);
        } catch (RuntimeException invalid) { requirements.put("draftCaptured", false); }
        // The existing interview falls back to an empty questionnaire on malformed output. Keep that visible.
        var choice = new BuildDecisions.Choice(1, BuildDecisions.Storage.MERN, BuildDecisions.Auth.ACCOUNTS, null);
        String answers = mapper.writeValueAsString(Map.of("kind", "requirements", "revision", 0, "buildDecision", choice,
                "answers", List.of(Map.of("question", "Where should data be saved?", "answer", "App backend + MongoDB"),
                        Map.of("question", "Should people sign in with real accounts?", "answer", "Real accounts"))));
        var approved = new ApprovedProjectMemory.Approved(UUID.randomUUID(), 0, answers, "", draft);
        return new ContractPlanner.Input(contractId, prompt, ApprovedProductScope.planningRequirements(approved, mapper),
                String.join("\n", Starter.MERN.files("Synthetic planner batch").keySet()), choice,
                new BuildContract.Limits(60, 150, 8192, 900000), "client/src/App.tsx");
    }

    /** Re-validates saved proposals with the current validator and normalizers; samples without one are carried over. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> replay(ObjectMapper mapper, Path batch, Path fixture, Path output) throws IOException {
        var prompts = new HashMap<String, String>();
        for (var item : mapper.readTree(Files.readString(fixture)).path("cases")) prompts.put(item.path("name").asString(), prompt(mapper, item));
        var report = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> samples = new ArrayList<>();
        report.put("schemaVersion", 1); report.put("correctionCalls", 0); report.put("replayOf", batch.toString());
        report.put("scope", "offline replay of saved planner outputs with the current validator and normalizers; no model calls");
        report.put("samples", samples);
        var planner = new ContractPlanner(mapper);
        for (var saved : mapper.readTree(Files.readString(batch.resolve("report.json"))).path("samples")) {
            Map<String, Object> sample = mapper.convertValue(saved, LinkedHashMap.class);
            samples.add(sample);
            String id = saved.path("id").asString();
            Path proposal = batch.resolve(id + "-planner.json");
            if (!"OK".equals(saved.path("planning").path("status").asString()) || !Files.isRegularFile(proposal)) continue;
            String prompt = prompts.get(saved.path("case").asString());
            if (prompt == null) throw new IllegalArgumentException("Replay case is missing from the fixture: " + id);
            var requirements = mapper.readValue(Files.readString(batch.resolve(id + "-requirements.json")), LlmClient.LlmResult.class);
            var input = plannerInput(mapper, UUID.fromString(saved.path("contractId").asString()), prompt, requirements,
                    (Map<String, Object>) sample.get("requirements"));
            var result = mapper.readValue(Files.readString(proposal), LlmClient.LlmResult.class);
            var original = new LinkedHashMap<String, Object>();
            original.put("outcome", sample.get("outcome"));
            original.put("signature", saved.path("normalized").path("signature").asString(null));
            sample.put("original", original); sample.put("raw", inspect(planner, input, result, false));
            var normalized = inspect(planner, input, result, true); sample.put("normalized", normalized);
            sample.put("outcome", Boolean.TRUE.equals(normalized.get("valid")) ? "VALID" : "REJECTED");
            boolean changed = !Objects.equals(original.get("outcome"), sample.get("outcome"))
                    || !Objects.equals(original.get("signature"), normalized.get("signature"));
            System.out.println("REPLAY_SAMPLE " + id + " " + sample.get("outcome") + (changed ? " CHANGED " : " ")
                    + Objects.toString(normalized.get("signature"), ""));
        }
        summarize(report, samples);
        Files.writeString(output.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private static final class Prepared extends RuntimeException {
        final LlmClient.LlmRequest request;
        Prepared(LlmClient.LlmRequest request) { this.request = request; }
    }

    static LlmClient.LlmRequest prepare(ContractPlanner planner, ContractPlanner.Input input, AgentProperties.ModelSelection route) {
        try { planner.plan(input, route, request -> { throw new Prepared(request); }); }
        catch (Prepared prepared) { return prepared.request; }
        throw new IllegalStateException("Expected exactly one prepared planner request");
    }

    LlmClient.LlmResult invoke(LlmClient.LlmRequest request, CallPurpose purpose, Map<String, Object> call, String filename) throws IOException {
        requireFreeRoute(new AgentProperties.ModelSelection(request.provider(), request.model()));
        // No route fallback or correction: measure independent first proposals and expose 429 separately.
        Duration limit = Duration.ofMillis(properties.getPlanning().getRouteTimeoutMs());
        var bounded = new LlmClient.LlmRequest(request.provider(), request.model(), request.messages(), request.tools(), request.temperature(),
                request.budget().compareTo(limit) < 0 ? request.budget() : limit, request.toolChoice(), request.maxTokens(), request.jsonSchema());
        call.put("route", request.provider() + ":" + request.model());
        long started = System.nanoTime();
        var partial = new StringBuilder();
        try (var context = LlmCallContext.purpose(purpose, false)) {
            var result = client.chatStreaming(bounded, partial::append);
            call.put("status", "OK"); call.put("usage", result.usage()); call.put("finishReason", result.finishReason());
            call.put("responseCharacters", result.content() == null ? 0 : result.content().length());
            Files.writeString(output.resolve(filename + ".json"), mapper.writeValueAsString(result));
            return result;
        } catch (LlmException failure) {
            call.put("status", rateLimited(failure) ? "RATE_LIMIT" : "PROVIDER_FAILURE");
            call.put("failureType", failure.getClass().getSimpleName());
            call.put("responseCharacters", partial.length());
            call.put("usage", failure instanceof LlmStreamException stream ? stream.usage() : null);
            return null;
        } finally {
            call.put("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
            System.out.println("BATCH_CALL " + filename + " " + mapper.writeValueAsString(call));
        }
    }

    static boolean rateLimited(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof RetryableLlmException retryable && retryable.rateLimited()
                    || cause instanceof LlmHttpException http && http.status() == 429
                    || cause instanceof LlmStreamException stream && Integer.valueOf(429).equals(stream.providerCode())) return true;
        return false;
    }

    static Map<String, Object> inspect(ContractPlanner planner, ContractPlanner.Input input, LlmClient.LlmResult proposal, boolean normalize) {
        var result = new LinkedHashMap<String, Object>();
        var events = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (normalize) { events.start(); logger.addAppender(events); }
        try {
            var validated = planner.validate(input, proposal, normalize);
            result.put("valid", true); result.put("browserActions", validated.contract().acceptance().browser().size());
            result.put("apiActions", validated.contract().acceptance().api().size());
        } catch (ContractPlanner.Rejected rejected) {
            result.put("valid", false); result.put("signature", rejected.signature()); result.put("reason", rejected.getMessage());
        } finally {
            if (normalize) {
                logger.detachAppender(events); events.stop();
                result.put("normalizationEvents", events.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("planner_") || message.startsWith("acceptance_")).toList());
            }
        }
        return result;
    }

    static void summarize(Map<String, Object> report, List<Map<String, Object>> samples) {
        long proposals = samples.stream().filter(sample -> sample.containsKey("normalized")).count();
        long valid = samples.stream().filter(sample -> "VALID".equals(sample.get("outcome"))).count();
        report.put("attemptedSamples", samples.size()); report.put("proposals", proposals); report.put("validAfterNormalization", valid);
        report.put("normalizedValidityPercent", proposals == 0 ? 0 : 100.0 * valid / proposals);
        report.put("rateLimitSamples", samples.stream().filter(sample -> "RATE_LIMIT".equals(sample.get("outcome"))).count());
        report.put("providerFailureSamples", samples.stream().filter(sample -> "PROVIDER_FAILURE".equals(sample.get("outcome"))).count());
        report.put("rejectionCounting", "First blocking rule per raw/normalized proposal; not an exhaustive defect count");
    }
}
