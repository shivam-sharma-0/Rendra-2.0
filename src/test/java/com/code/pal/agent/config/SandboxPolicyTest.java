package com.code.pal.agent.config;

import com.code.pal.agent.workspace.SandboxPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SandboxPolicyTest {

    private final SandboxPort port = mock(SandboxPort.class);

    @Test
    void sandboxEnabledIsSafeOnceItsEnvironmentIsVerified() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(true);

        assertDoesNotThrow(() -> new SandboxPolicy(props, port).requireSafeExecution());
        verify(port).verifyEnvironment();
    }

    @Test
    void aMisconfiguredSandboxRefusesToStart() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(true);
        doThrow(new IllegalStateException("not the same folder")).when(port).verifyEnvironment();

        assertThrows(IllegalStateException.class, () -> new SandboxPolicy(props, port).requireSafeExecution());
    }

    @Test
    void sandboxDisabledRefusesToStart() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(false);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new SandboxPolicy(props, port).requireSafeExecution());

        assertTrue(refused.getMessage().contains("AGENT_SANDBOX_REQUIRED=true"), refused.getMessage());
    }

    @Test
    void hostExecutionNeedsAnExplicitOptIn() {
        AgentProperties props = new AgentProperties();
        props.getSandbox().setRequired(false);
        props.getSandbox().setAllowHostExecution(true);

        assertDoesNotThrow(() -> new SandboxPolicy(props, port).requireSafeExecution());
    }
}
