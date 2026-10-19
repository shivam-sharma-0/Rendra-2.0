package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DockerSupabaseOwnershipTest {
    private final UUID project = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final AgentProperties properties = new AgentProperties();
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final List<List<String>> calls = new ArrayList<>();
    private final AtomicBoolean owned = new AtomicBoolean();
    private final RunCancellation cancellation = new RunCancellation(run);
    private boolean stopDuringCreation;
    private final DockerSupabaseScratch scratch = new DockerSupabaseScratch(properties, new ObjectMapper(), (args, timeout, stdin, limit) -> {
        calls.add(List.copyOf(args));
        if (args.getFirst().equals("create") || args.subList(0, 2).equals(List.of("network", "create"))) {
            assertTrue(owned.get(), "Resource creation must be serialized with lease recovery/takeover");
            assertFalse(RunCancellation.current().isCancelled(), "Bounded creation completes before observing Stop");
            if (stopDuringCreation) cancellation.cancel();
            return new CommandResult(true, 0, "fixture-id", "");
        }
        return new CommandResult(false, 1, "No such object", "");
    });

    DockerSupabaseOwnershipTest() {
        properties.getSupabaseValidation().setEnabled(true);
        scratch.useLeases(leases);
        doAnswer(call -> {
            owned.set(true);
            try { return call.getArgument(1, Supplier.class).get(); }
            finally { owned.set(false); }
        }).when(leases).guarded(eq(project), any(Supplier.class));
    }

    @Test
    void missingOrCrossRunFencesCannotStartAnyResources() {
        assertThrows(LeaseLostException.class, () -> scratch.open(project, run, List.of()));
        try (var ignored = RunFence.bind(new RunFence(project, UUID.randomUUID(), UUID.randomUUID(), 1))) {
            assertThrows(LeaseLostException.class, () -> scratch.open(project, run, List.of()));
        }
        assertTrue(calls.stream().noneMatch(args -> args.getFirst().equals("create") || args.contains("start") || args.contains("create")));
        verify(leases, never()).guarded(any(), any(Supplier.class));
    }

    @Test
    void aRejectedLeaseNeverReachesDockerCreation() {
        doThrow(new LeaseLostException()).when(leases).guarded(eq(project), any(Supplier.class));
        try (var ignored = RunFence.bind(new RunFence(project, run, UUID.randomUUID(), 1))) {
            assertThrows(LeaseLostException.class, () -> scratch.open(project, run, List.of()));
        }
        assertTrue(calls.stream().allMatch(args -> args.contains("inspect")));
    }

    @Test
    void stopDuringCreationFinishesTheOwnedMutationThenCleansWithoutStartingAnyProcess() {
        stopDuringCreation = true;
        try (var signal = RunCancellation.bind(cancellation);
                var fence = RunFence.bind(new RunFence(project, run, UUID.randomUUID(), 1))) {
            assertThrows(LlmCancelledException.class, () -> scratch.open(project, run, List.of()));
        }
        verify(leases).guarded(eq(project), any(Supplier.class));
        assertEquals(1, calls.stream().filter(args -> args.contains("create")).count());
        assertTrue(calls.stream().noneMatch(args -> args.getFirst().equals("start")));
    }
}
