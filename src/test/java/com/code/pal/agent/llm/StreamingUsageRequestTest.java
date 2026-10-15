package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StreamingUsageRequestTest {
    @Test
    void shippedUsageDefaultsAreConservativeForMistralAndEnabledForNvidia() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var config = Objects.requireNonNull(yaml.getObject());
        assertEquals("${MISTRAL_INCLUDE_STREAM_USAGE:false}",
                config.getProperty("app.agent.providers.mistral.include-stream-usage"));
        assertEquals("${NVIDIA_INCLUDE_STREAM_USAGE:true}",
                config.getProperty("app.agent.providers.nvidia.include-stream-usage"));
    }

    @Test
    void conversationalCapIsClampedInConfiguration() {
        var config = new AgentProperties.Llm();
        config.setSmallTalkMaxOutputTokens(2_000);
        assertEquals(300, config.getSmallTalkMaxOutputTokens());
        config.setSmallTalkMaxOutputTokens(120);
        assertEquals(120, config.getSmallTalkMaxOutputTokens());
        config.setSmallTalkMaxOutputTokens(0);
        assertEquals(1, config.getSmallTalkMaxOutputTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mistral", "nvidia"})
    void configuredProviderRequestsUsageInTheStreamingBody(String name) {
        var properties = new AgentProperties();
        var provider = new AgentProperties.Provider();
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        provider.setIncludeStreamUsage(true);
        properties.getProviders().put(name, provider);
        var mapper = new ObjectMapper();
        var client = new OpenAiCompatibleLlmClient(properties, mapper);
        var request = new LlmClient.LlmRequest(name, "fixture", List.of(), List.of(), 0);
        var body = mapper.valueToTree(client.buildBody(request));
        assertEquals(true, body.path("stream").asBoolean());
        assertEquals(true, body.at("/stream_options/include_usage").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mistral", "nvidia", "other"})
    void disabledUsageFlagOmitsUnsupportedProviderOption(String name) {
        var properties = new AgentProperties();
        var provider = new AgentProperties.Provider();
        provider.setBaseUrl("http://127.0.0.1:1/v1");
        properties.getProviders().put(name, provider);
        var client = new OpenAiCompatibleLlmClient(properties, new ObjectMapper());
        var request = new LlmClient.LlmRequest(name, "fixture", List.of(), List.of(), 0);
        assertFalse(client.buildBody(request).containsKey("stream_options"));
    }
}
