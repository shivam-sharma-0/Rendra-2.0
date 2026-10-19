package com.code.pal.agent.route;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelRoutesTest {
    private AgentProperties properties() {
        var properties = new AgentProperties();
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.getModels().setBuild(List.of("mistral:first", "nvidia:second", "mistral:third"));
        return properties;
    }

    @Test
    void repeatedDefectsTryOneConfiguredAlternateWithoutGlobalProviderCooldown() {
        var properties = properties();
        var routes = new ModelRoutes(properties, ProjectType.BUILD);
        var first = routes.next();
        assertFalse(routes.failedValidation("same defect"));
        assertTrue(routes.failedValidation("same defect"));
        assertEquals("second", routes.next().model());
        assertTrue(properties.available(first));
        assertEquals(first, new ModelRoutes(properties, ProjectType.BUILD).next());
        assertFalse(routes.failedValidation("same defect"));
        assertFalse(routes.failedValidation("same defect"), "one escalation per run is the configured ceiling");
    }

    @Test
    void nonConvergingRepairsTryAnAlternateEvenWhenTheirDiagnosticsChange() {
        var properties = properties();
        var routes = new ModelRoutes(properties, ProjectType.BUILD);
        var first = routes.next();
        assertFalse(routes.failedValidation("defect A"));
        assertTrue(routes.failedValidation("new regression B"));
        assertEquals("second", routes.next().model());
        assertTrue(properties.available(first));
        assertFalse(routes.failedValidation("defect C"));
        assertFalse(routes.failedValidation("defect D"), "the existing one-escalation ceiling still applies");
    }

    @Test
    void disabledRevisionTriggerPreservesRepeatedDiagnosticPolicyAndDisabledEscalation() {
        var properties = properties();
        properties.getRepair().setFailedRevisionThreshold(0);
        var routes = new ModelRoutes(properties, ProjectType.BUILD);
        var first = routes.next();
        assertFalse(routes.failedValidation("defect A"));
        assertFalse(routes.failedValidation("defect B"));
        properties.getRepair().setMaxModelEscalations(0);
        assertFalse(routes.failedValidation("defect B"));
        assertEquals(first, routes.next());
    }

    @Test
    void providerFallbackStartsItsOwnFailureHistory() {
        var properties = properties();
        var routes = new ModelRoutes(properties, ProjectType.BUILD);
        var first = routes.next();
        assertFalse(routes.failedValidation("defect A"));
        routes.failed(first, new LlmException("transport unavailable"), () -> {});
        assertEquals("second", routes.next().model());
        assertFalse(routes.failedValidation("defect B"));
        assertTrue(routes.failedValidation("defect C"));
        assertEquals("third", routes.next().model());
    }

    @Test
    void cancellationTerminalRequestsAndPartialOutputNeverRotateOrCoolDown() {
        var properties = properties();
        var routes = new ModelRoutes(properties, ProjectType.BUILD);
        var first = routes.next();
        for (var failure : List.of(new LlmCancelledException(), new LlmHttpException(400, "invalid"),
                new LlmException("failed after partial output"))) {
            assertThrows(LlmException.class, () -> routes.failed(first, failure, () -> {}));
            assertEquals(first, routes.next());
            assertTrue(properties.available(first));
        }
    }

    @Test
    void providerFailureRemainsExcludedAndKeepsItsOriginalFailure() {
        var properties = properties();
        var routes = new ModelRoutes(properties, ProjectType.BUILD);
        var first = routes.next();
        var failure = new LlmException("provider unavailable");
        routes.failed(first, failure, () -> {});
        assertEquals("second", routes.next().model());
        assertSame(failure, routes.lastFailure());
        assertFalse(properties.available(first));
    }
}
