package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Credential-free Docker ownership/discovery and exact-resource cleanup, shared by normal close and recovery. */
final class SupabaseScratchResources {
    static final String ENVIRONMENT = "com.codepal.supabase-validation";
    static final String FORMAT = "com.codepal.supabase-validation-format";
    static final String NAMESPACE = "com.codepal.namespace";
    static final String PROJECT = "com.codepal.project";
    static final String RUN = "com.codepal.run";
    private static final List<String> SERVICES = List.of("proxy", "rest", "auth", "db");

    record Identity(String name, UUID project, UUID run) {
        Identity {
            if (name == null || !name.matches("codepal-supa-check-[a-f0-9-]{36}") || project == null || run == null) {
                throw new IllegalArgumentException("Invalid isolated Supabase ownership");
            }
            UUID.fromString(name.substring("codepal-supa-check-".length()));
        }
    }

    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final DockerProjectEnvironment.CommandRunner docker;
    private final String namespace;

    SupabaseScratchResources(AgentProperties properties, ObjectMapper mapper, DockerProjectEnvironment.CommandRunner docker) {
        this.properties = properties;
        this.mapper = mapper;
        this.docker = docker;
        namespace = WorkspaceNamespace.of(Path.of(properties.getWorkspaceBasePath()));
    }

    List<String> labels(Identity identity) {
        var args = new ArrayList<String>();
        Map.of(ENVIRONMENT, identity.name(), FORMAT, "1", NAMESPACE, namespace,
                PROJECT, identity.project().toString(), RUN, identity.run().toString())
                .forEach((key, value) -> args.addAll(List.of("--label", key + "=" + value)));
        return List.copyOf(args);
    }

    List<String> discover() {
        var result = command(List.of("network", "ls", "--filter", "label=" + FORMAT + "=1",
                "--filter", "label=" + NAMESPACE + "=" + namespace, "--format", "{{.Name}}"));
        if (!result.success()) throw unavailable();
        return result.stdout().lines().filter(line -> !line.isBlank()).sorted().toList();
    }

    Identity inspect(String name) {
        if (name == null || !name.matches("codepal-supa-check-[a-f0-9-]{36}")) throw unavailable();
        var result = command(List.of("network", "inspect", "--format", "{{json .}}", name));
        if (!result.success()) throw unavailable();
        var network = mapper.readTree(result.stdout());
        var labels = network.path("Labels");
        var identity = new Identity(name, UUID.fromString(labels.path(PROJECT).asString()), UUID.fromString(labels.path(RUN).asString()));
        if (!owned(network.path("Labels"), identity) || !network.path("Name").asString().equals(name)
                || !network.path("Internal").asBoolean(false) || !network.path("Driver").asString().equals("bridge")
                || !network.path("Containers").isObject()) throw unavailable();
        for (JsonNode container : network.path("Containers")) {
            if (SERVICES.stream().noneMatch(service -> container.path("Name").asString().equals(name + "-" + service))) throw unavailable();
        }
        return identity;
    }

    boolean remove(Identity identity) {
        boolean interrupted = Thread.interrupted();
        try (var ignored = RunCancellation.bind(RunCancellation.NONE)) {
            var network = command(List.of("network", "inspect", "--format", "{{json .}}", identity.name()));
            if (network.success()) {
                if (!inspect(identity.name()).equals(identity)) return false;
            } else if (!absent(network)) return false;
            for (String service : SERVICES) {
                String name = identity.name() + "-" + service;
                var inspect = command(List.of("container", "inspect", "--format", "{{json .Config.Labels}}", name));
                if (!inspect.success()) {
                    if (absent(inspect)) continue;
                    return false;
                }
                if (!owned(mapper.readTree(inspect.stdout()), identity)) return false;
                if (!command(List.of("rm", "-f", name)).success()) return false;
            }
            if (!network.success()) return true;
            // Removing the internal network also refuses an unexpected/late attached endpoint; never force it away.
            return command(List.of("network", "rm", identity.name())).success();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean owned(JsonNode labels, Identity identity) {
        return labels.isObject() && labels.path(ENVIRONMENT).asString().equals(identity.name())
                && labels.path(FORMAT).asString().equals("1") && labels.path(NAMESPACE).asString().equals(namespace)
                && labels.path(PROJECT).asString().equals(identity.project().toString()) && labels.path(RUN).asString().equals(identity.run().toString());
    }

    private static boolean absent(CommandResult result) {
        return result.started() && !result.timedOut() && !result.cancelled()
                && (result.stdout() + "\n" + result.error()).toLowerCase(Locale.ROOT).contains("no such");
    }

    private CommandResult command(List<String> args) {
        return docker.run(args, Duration.ofMillis(Math.max(1_000, properties.getSupabaseValidation().getCommandTimeoutMs())), null, 65_536);
    }

    private static SupabaseScratchException unavailable() {
        return new SupabaseScratchException("Isolated Supabase resource ownership could not be verified", false);
    }
}
