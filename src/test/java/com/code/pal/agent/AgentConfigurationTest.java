package com.code.pal.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.domain.ProjectType;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentConfigurationTest {
    @Test void shippedPlanningAndRequirementsPreferCodestralWithTheirOwnFallbacks() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var sources = new MutablePropertySources();
        sources.addFirst(new PropertiesPropertySource("application", yaml.getObject()));
        var config = new PropertySourcesPropertyResolver(sources);
        var properties = new AgentProperties();
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.getModels().setPlan(Arrays.asList(config.getProperty("app.agent.models.plan").split(",")));
        properties.getModels().setFreePlan(Arrays.asList(config.getProperty("app.agent.models.free-plan").split(",")));
        properties.getRequirements().setModels(Arrays.asList(config.getProperty("app.agent.requirements.models").split(",")));
        for (var mode : AgentProperties.BillingMode.values()) {
            properties.setBillingMode(mode);
            assertEquals("codestral-latest", properties.preferredModel(ProjectType.PLAN).model());
            assertEquals(List.of("mistral", "openrouter", "nvidia"),
                    properties.planningRoutes().stream().map(AgentProperties.ModelSelection::provider).toList());
            assertEquals("codestral-latest", properties.preferredRequirementsModel().model());
            assertEquals(List.of("mistral", "openrouter"),
                    properties.requirementsRoutes().stream().map(AgentProperties.ModelSelection::provider).toList());
        }
        assertEquals("90000", config.getProperty("app.agent.requirements.timeout-ms"));
        assertEquals("3", config.getProperty("app.agent.planning.max-route-attempts"));
    }

    @Test void applicationConfigurationParsesWithoutDuplicateKeysOrLoadingSecrets() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertNotNull(properties.getProperty("app.agent.planning.schema-providers"));
        assertNotNull(properties.getProperty("app.agent.context-budget-chars"));
    }
}
