package com.code.pal.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxPolicyTest {

    @Test
    void sandboxEnabledIsSafe() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(true);

        assertDoesNotThrow(() -> new SandboxPolicy(props).requireSafeExecution());
    }

    @Test
    void sandboxDisabledRefusesToStart() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(false);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new SandboxPolicy(props).requireSafeExecution());

        assertTrue(refused.getMessage().contains("AGENT_SANDBOX_REQUIRED=true"), refused.getMessage());
    }

    @Test
    void hostExecutionNeedsAnExplicitOptIn() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(false);
        props.getSandbox().setAllowHostExecution(true);

        assertDoesNotThrow(() -> new SandboxPolicy(props).requireSafeExecution());
    }
}
