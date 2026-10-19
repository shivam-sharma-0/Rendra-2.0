package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SupabaseScratchResourcesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final SupabaseScratchResources.Identity identity = new SupabaseScratchResources.Identity(
            "codepal-supa-check-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final List<List<String>> calls = new ArrayList<>();
    private final Map<String, Map<String, String>> containers = new HashMap<>();
    private Map<String, String> networkLabels;
    private boolean internal = true;
    private boolean networkExists = true;
    private boolean extraContainer;
    private CommandResult inspectFailure;
    private final SupabaseScratchResources resources = new SupabaseScratchResources(properties, mapper, (args, timeout, stdin, limit) -> run(args));

    SupabaseScratchResourcesTest() {
        networkLabels = labels();
        for (String service : List.of("proxy", "rest", "auth", "db")) containers.put(identity.name() + "-" + service, labels());
    }

    private Map<String, String> labels() {
        var result = new HashMap<String, String>();
        var args = resources.labels(identity);
        for (int index = 1; index < args.size(); index += 2) {
            String value = args.get(index);
            result.put(value.substring(0, value.indexOf('=')), value.substring(value.indexOf('=') + 1));
        }
        return result;
    }

    private CommandResult run(List<String> args) {
        assertFalse(RunCancellation.current().isCancelled(), "Cleanup must remain possible after Stop");
        calls.add(List.copyOf(args));
        if (args.subList(0, 2).equals(List.of("network", "ls"))) return ok(identity.name());
        if (args.subList(0, 2).equals(List.of("network", "inspect"))) {
            if (inspectFailure != null) return inspectFailure;
            if (!networkExists) return missing();
            var attached = new HashMap<String, Object>();
            containers.keySet().forEach(name -> attached.put(name, Map.of("Name", name)));
            if (extraContainer) attached.put("unrecognized", Map.of("Name", "another-project-service"));
            return ok(mapper.writeValueAsString(Map.of("Name", identity.name(), "Labels", networkLabels,
                    "Internal", internal, "Driver", "bridge", "Containers", attached)));
        }
        if (args.subList(0, 2).equals(List.of("container", "inspect"))) {
            var labels = containers.get(args.getLast());
            return labels == null ? missing() : ok(mapper.writeValueAsString(labels));
        }
        if (args.getFirst().equals("rm")) { containers.remove(args.getLast()); return ok(args.getLast()); }
        if (args.subList(0, 2).equals(List.of("network", "rm"))) { networkExists = false; return ok(identity.name()); }
        throw new AssertionError("Unexpected Docker command: " + args);
    }

    private static CommandResult ok(String text) { return new CommandResult(true, 0, text, ""); }
    private static CommandResult missing() { return new CommandResult(false, 1, "Error response from daemon: No such object", ""); }
    private long removals() { return calls.stream().filter(args -> args.getFirst().equals("rm") || args.subList(0, 2).equals(List.of("network", "rm"))).count(); }

    @Test
    void discoversOnlyTheCurrentWorkspaceAndVersionThenProvesAllOwnershipBeforeCleanup() {
        assertEquals(List.of(identity.name()), resources.discover());
        assertTrue(calls.getFirst().contains("label=" + SupabaseScratchResources.FORMAT + "=1"));
        assertTrue(calls.getFirst().contains("label=" + SupabaseScratchResources.NAMESPACE + "=" + networkLabels.get(SupabaseScratchResources.NAMESPACE)));
        assertEquals(identity, resources.inspect(identity.name()));
        assertTrue(resources.remove(identity));
        assertTrue(containers.isEmpty());
        assertFalse(networkExists);
        assertEquals(5, removals());
        assertTrue(resources.remove(identity), "Proved absence is idempotent");
    }

    @Test
    void aDifferentNamespaceOrProjectCannotBeRemoved() {
        for (String key : List.of(SupabaseScratchResources.NAMESPACE, SupabaseScratchResources.PROJECT, SupabaseScratchResources.RUN,
                SupabaseScratchResources.FORMAT, SupabaseScratchResources.ENVIRONMENT)) {
            networkLabels = labels();
            networkLabels.put(key, key.equals(SupabaseScratchResources.NAMESPACE) ? "other-workspace" : UUID.randomUUID().toString());
            if (key.equals(SupabaseScratchResources.PROJECT) || key.equals(SupabaseScratchResources.RUN)) {
                assertFalse(resources.remove(identity));
            } else {
                assertThrows(RuntimeException.class, () -> resources.remove(identity));
            }
            assertEquals(0, removals());
        }
    }

    @Test
    void unexpectedAttachedContainersAndExternalNetworksAreRetained() {
        extraContainer = true;
        assertThrows(RuntimeException.class, () -> resources.remove(identity));
        assertEquals(0, removals());
        extraContainer = false;
        internal = false;
        assertThrows(RuntimeException.class, () -> resources.remove(identity));
        assertEquals(0, removals());
    }

    @Test
    void mismatchedContainerLabelsRefuseRemovalAndRetainTheNetwork() {
        containers.get(identity.name() + "-proxy").put(SupabaseScratchResources.NAMESPACE, "wrong-workspace");
        assertFalse(resources.remove(identity));
        assertEquals(0, removals());
        assertTrue(networkExists);
    }

    @Test
    void unavailableOrTimedOutInspectionCannotBeTreatedAsProvedAbsence() {
        for (var failure : List.of(new CommandResult(false, -1, "No such object", CommandResult.NOT_STARTED),
                new CommandResult(false, -1, "No such object", CommandResult.TIMED_OUT),
                new CommandResult(false, 1, "Docker daemon unavailable", ""))) {
            inspectFailure = failure;
            assertFalse(resources.remove(identity));
            assertEquals(0, removals());
        }
    }

    @Test
    void stopAndThreadInterruptionAreRestoredAfterBoundedCleanup() {
        var cancellation = new RunCancellation(identity.run());
        cancellation.cancel();
        try (var ignored = RunCancellation.bind(cancellation)) {
            Thread.currentThread().interrupt();
            try {
                assertTrue(resources.remove(identity));
                assertTrue(Thread.currentThread().isInterrupted());
                assertSame(cancellation, RunCancellation.current());
            } finally { Thread.interrupted(); }
        }
    }
}
