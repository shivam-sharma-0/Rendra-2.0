package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SupabaseScratch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SupabaseApiCancellationTest {
    @Test
    void stoppedAccountValidationUsesTheRunnersCancellationSignalBeforeAnyTransport() {
        var cancellation = new RunCancellation(UUID.randomUUID());
        cancellation.cancel();
        var session = mock(SupabaseScratch.Session.class);
        try (var ignored = RunCancellation.bind(cancellation)) {
            assertThrows(LlmCancelledException.class,
                    () -> new SupabaseApiAcceptance(new ObjectMapper(), new AgentProperties()).check(session, List.of(), true));
        }
        verify(session, never()).endpoint();
        verify(session, never()).restartRest();
    }
}
