package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.llm.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.DeserializationFeature;

/** Workers return data only. They receive no file port, shell, connector or write capability. */
@Component
public final class UnitGenerator {
    public record FileView(String version, String content) {
    }

    public record Delta(String path, String expectedVersion, String content) {
    }

    public record Output(List<Delta> files) {
        public Output {
            files = List.copyOf(files);
        }
    }

    public record Input(BuildContract contract, BuildContract.Unit unit, String request, String approvedRequirements,
            Map<String, FileView> snapshot, String repairFeedback) {
        public Input {
            snapshot = Map.copyOf(snapshot);
        }
    }

    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public UnitGenerator(ObjectMapper mapper) {
        this(mapper, new AgentProperties());
    }

    @Autowired
    public UnitGenerator(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public Output generate(Input input, AgentProperties.ModelSelection model,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall) {
        String data = mapper.writeValueAsString(input);
        String policy = SYSTEM + ContractImports.policy(input.contract());
        if (data.length() + policy.length() > Math.max(1, properties.getContextBudgetChars()))
            throw new IllegalArgumentException("Focused unit context exceeds capacity; split this unit");
        var messages = new ArrayList<LlmClient.LlmMessage>(
                List.of(LlmClient.LlmMessage.system(policy), LlmClient.LlmMessage.user(data)));
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmClient.LlmResult response;
            try (var ignored = LlmCallContext.purpose(attempt == 0 ? CallPurpose.UNIT : CallPurpose.UNIT_CORRECTION,
                    true)) {
                var request = new LlmClient.LlmRequest(model.provider(), model.model(), List.copyOf(messages),
                        List.of(), 0.1,
                        Duration.ofMillis(Math.min(60000, input.contract().limits().timeoutMs())))
                        .withMaxTokens(input.contract().limits().outputTokens());
                if ("mistral".equalsIgnoreCase(model.provider()))
                    request = request.withJsonSchema(ContractResponseSchema.unit(input, mapper));
                response = checkedCall.apply(request);
            }
            try {
                if (response == null || response.hasToolCalls() || response.content() == null
                        || response.content().length() > 48000 || "length".equals(response.finishReason()))
                    throw new IllegalArgumentException("Incomplete unit data");
                String content = ModelJson.object(response.content());
                Map<?, ?> object = mapper.readerFor(Map.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(content);
                var json = mapper.readTree(content);
                if (!object.keySet().equals(Set.of("files")) || !json.path("files").isArray())
                    throw new IllegalArgumentException("Unexpected unit output fields");
                for (var entry : json.path("files"))
                    if (!mapper.readValue(entry.toString(), Map.class).keySet()
                            .equals(Set.of("path", "expectedVersion", "content")))
                        throw new IllegalArgumentException("Unexpected mutation fields");
                var result = mapper.readValue(content, Output.class);
                validate(input, result);
                return result;
            } catch (RuntimeException invalid) {
                String reason = ContractPlanner.schemaFeedback(invalid);
                if (attempt == 1)
                    throw new IllegalArgumentException("Unit output correction failed: " + reason + "; no files merged", invalid);
                messages.add(LlmClient.LlmMessage.user(PromptLoader.load(Prompt.UNIT_OUTPUT_CORRECTION)
                        .replace("{{reason}}", reason)));
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    public static void validate(Input input, Output output) {
        if (output == null || output.files().isEmpty() || output.files().size() > 12)
            throw new IllegalArgumentException("Invalid unit file count");
        var seen = new HashSet<String>();
        var assets = new HashSet<String>();
        input.contract().assets().forEach(a -> assets.add(a.destination()));
        for (var file : output.files()) {
            BuildContract.canonical(file.path());
            if (!input.unit().ownedPaths().contains(file.path()) || assets.contains(file.path())
                    || !seen.add(file.path()))
                throw new IllegalArgumentException("Unit ownership violation");
            var source = input.snapshot().get(file.path());
            if (source == null || !Objects.equals(source.version(), file.expectedVersion()))
                throw new IllegalArgumentException("Unit snapshot version mismatch");
            if (file.content() != null && (file.content().length() > 48000 || file.content().indexOf('\0') >= 0))
                throw new IllegalArgumentException("Unit output exceeds capacity");
            ContractImports.validate(input.contract(), input.unit(), file.path(), file.content());
        }
        var needed = new HashSet<>(input.unit().ownedPaths());
        needed.removeAll(assets);
        if (!needed.equals(seen))
            throw new IllegalArgumentException("Unit must account for every owned code path");
    }

    private static final String SYSTEM = PromptLoader.load(Prompt.UNIT_GENERATOR);
}
