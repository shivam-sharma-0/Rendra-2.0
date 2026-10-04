package com.code.pal.agent.config;

import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Generated code is untrusted. Agent validation executes it (npm scripts, build configs), so it must
 * run in the project sandbox. Running it on the backend host is allowed only when explicitly opted in
 * (local development without Docker), and then loudly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxPolicy {

    private final AgentProperties properties;
    private final SandboxPort sandboxPort;

    /**
     * @throws IllegalStateException when the sandbox is off and host execution was not opted into, or
     *                               when the sandbox does not match its configuration
     */
    public void requireSafeExecution() {
        AgentProperties.Sandbox sandbox = properties.getSandbox();
        if (sandbox.isRequired()) {
            sandboxPort.verifyEnvironment();
            return;
        }
        if (!sandbox.isAllowHostExecution()) {
            throw new IllegalStateException("The agent sandbox is disabled (app.agent.sandbox.required=false), so "
                    + "generated code would run directly on this host. Enable the sandbox "
                    + "(AGENT_SANDBOX_REQUIRED=true), or for local development only set "
                    + "AGENT_SANDBOX_ALLOW_HOST_EXECUTION=true.");
        }
        log.warn("SECURITY: agent sandbox disabled; generated code runs on this host "
                + "(app.agent.sandbox.allow-host-execution=true). Never use this outside local development.");
    }
}
