package com.code.pal.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentConfigurationTest {
    @Test void applicationConfigurationParsesWithoutDuplicateKeysOrLoadingSecrets() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var properties = yaml.getObject();
        assertNotNull(properties);
        assertNotNull(properties.getProperty("app.agent.planning.schema-providers"));
        assertNotNull(properties.getProperty("app.agent.context-budget-chars"));
    }
}
