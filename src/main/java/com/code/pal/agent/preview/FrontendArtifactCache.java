package com.code.pal.agent.preview;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.contract.StaticAssets;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.domain.ProjectType;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.UUID;

/** Private bounded run memory, shared by successful compiler and runtime phases; never a validation receipt. */
@Service
public final class FrontendArtifactCache {
    public record Token(UUID project, UUID run, String identity, List<String> command, List<String> outputs) {
        public Token { command = List.copyOf(command); outputs = List.copyOf(outputs); }
    }
    private record Body(byte[] bytes, String hash) {}
    private record Artifact(String output, Map<String, Body> files, long bytes) {}
    private final AgentProperties properties;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final Map<UUID, LinkedHashMap<Token, Artifact>> runs = new LinkedHashMap<>();
    private long retainedBytes;

    public FrontendArtifactCache(AgentProperties properties, ProjectFiles files, SandboxPort sandbox) {
        this.properties = properties;
        this.files = files;
        this.sandbox = sandbox;
    }

    public Token token(AgentContext context, String root, StackPack stack, StackPack.PreviewBuild build) {
        if (!properties.getBuildArtifacts().isEnabled() || !properties.getSandbox().isRequired() || build == null
                || context.mode() != ProjectType.BUILD || context.runId() == null
                || !properties.getSandbox().getImage().matches(".+@sha256:[a-f0-9]{64}")) return null;
        running();
        try {
            // Unknown/custom build commands/configurations keep normal compilation. No npm-script equivalence is guessed.
            boolean staticCompiler = stack.id().equals("static-site") && build.command().equals(StaticAssets.command());
            if (staticCompiler) StaticAssets.require(files, context.projectId(), root);
            boolean vite = stack.id().equals("node") && build.command().contains("node_modules/vite/bin/vite.js")
                    && files.regularFile(context.projectId(), root, "vite.config.ts")
                    && files.readText(context.projectId(), root, "vite.config.ts").equals(Starter.VITE_REACT.files(null).get("vite.config.ts"));
            if (!staticCompiler && !vite) return null;
            for (String output : build.outputDirectories()) ProjectPaths.agentPath(output, false);
            String identity = sourceIdentity(context.projectId(), root, build.outputDirectories()) + "\n" + stack.dependencyFingerprint(files, context.projectId(), root)
                    + "\n" + properties.getSandbox().getImage();
            return new Token(context.projectId(), context.runId(), ContractStore.hash(identity), build.command(), build.outputDirectories());
        } catch (IllegalArgumentException | IllegalStateException unsupported) {
            return null;
        }
    }

    /** Called only after a successful command, and only if its full input identity is still unchanged. */
    public boolean capture(Token before, AgentContext context, String root, StackPack stack, StackPack.PreviewBuild build) {
        if (before == null || !before.equals(token(context, root, stack, build))) return false;
        var limits = properties.getBuildArtifacts();
        try {
            String output = before.outputs().stream().filter(path -> files.regularFile(before.project(), root + "/" + path, "index.html"))
                    .findFirst().orElse(null);
            if (output == null) return false;
            String source = root + "/" + output;
            var bodies = new LinkedHashMap<String, Body>();
            var portable = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            long bytes = 0;
            for (var entry : files.list(before.project(), source, 24, Set.of())) {
                running();
                if (entry.directory()) { if (entry.path().split("/").length >= 24) return false; continue; }
                String path = ProjectPaths.agentPath(entry.path(), false);
                if (!portable.add(path) || bodies.size() >= Math.max(1, limits.getMaxFiles()) || entry.size() > limits.getMaxFileBytes()) return false;
                byte[] body = files.readBytes(before.project(), source, path, Math.max(1, limits.getMaxFileBytes()));
                if ((bytes += body.length) > Math.max(1, limits.getMaxRunBytes())) return false;
                bodies.put(path, new Body(body, hash(body)));
            }
            if (!bodies.containsKey("index.html") || !before.equals(token(context, root, stack, build))) return false;
            return retain(before, new Artifact(output, Map.copyOf(bodies), bytes));
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return false;
        }
    }

    /** Restore into a private disposable build copy. Every browser/outcome check still runs afterwards. */
    public String restore(Token expected, AgentContext context, String root, StackPack stack, StackPack.PreviewBuild build) {
        if (expected == null || !expected.equals(token(context, root, stack, build))) return null;
        String normalized = ProjectPaths.normalize(root, true);
        if (!normalized.startsWith(ProjectPaths.INTERNAL + "/")) return null;
        Artifact artifact;
        synchronized (this) { artifact = runs.getOrDefault(expected.run(), new LinkedHashMap<>()).get(expected); }
        if (artifact == null) return null;
        running();
        String target = normalized + "/" + artifact.output();
        sandbox.removeTree(expected.project(), target);
        for (var entry : artifact.files().entrySet()) {
            running();
            files.writeBytes(expected.project(), target + "/" + entry.getKey(), entry.getValue().bytes().clone());
            if (!files.version(expected.project(), target, entry.getKey()).equals("sha256:" + entry.getValue().hash())) {
                throw new IllegalStateException("Restored frontend artifact proof failed");
            }
        }
        if (!expected.equals(token(context, root, stack, build))) throw new IllegalStateException("Frontend source changed during artifact restore");
        return target;
    }

    private synchronized boolean retain(Token token, Artifact artifact) {
        var limits = properties.getBuildArtifacts();
        var entries = runs.computeIfAbsent(token.run(), ignored -> new LinkedHashMap<>());
        Artifact previous = entries.remove(token);
        if (previous != null) retainedBytes -= previous.bytes();
        while (!entries.isEmpty() && (entries.size() >= Math.max(1, limits.getMaxEntriesPerRun())
                || entries.values().stream().mapToLong(Artifact::bytes).sum() + artifact.bytes() > Math.max(1, limits.getMaxRunBytes()))) {
            var oldest = entries.keySet().iterator();
            retainedBytes -= entries.remove(oldest.next()).bytes();
        }
        if (retainedBytes + artifact.bytes() > Math.max(1, limits.getMaxTotalBytes())) { if (entries.isEmpty()) runs.remove(token.run()); return false; }
        entries.put(token, artifact);
        retainedBytes += artifact.bytes();
        return true;
    }

    public synchronized void clear(UUID run) {
        var removed = runs.remove(run);
        if (removed != null) retainedBytes -= removed.values().stream().mapToLong(Artifact::bytes).sum();
    }

    private String sourceIdentity(UUID project, String root, List<String> outputs) {
        var versions = new TreeMap<String, String>();
        var entries = files.list(project, root, 32, Set.of(".git", ".codepal", "node_modules"));
        if (entries.size() > 5000) throw new IllegalStateException("Artifact input proof exceeds limits");
        for (var entry : entries) {
            running();
            if (outputs.stream().anyMatch(output -> entry.path().equals(output) || entry.path().startsWith(output + "/"))) continue;
            if (entry.directory()) {
                if (entry.path().split("/").length >= 32) throw new IllegalStateException("Artifact input proof is incomplete");
                continue;
            }
            String version = files.version(project, root, entry.path());
            if (!version.startsWith("sha256:")) throw new IllegalStateException("Artifact source changed");
            versions.put(entry.path(), version);
        }
        var proof = new StringBuilder();
        versions.forEach((path, version) -> proof.append(path).append('\0').append(version).append('\n'));
        return ContractStore.hash(proof.toString());
    }

    private static void running() {
        if (Thread.currentThread().isInterrupted() || RunCancellation.current().isCancelled()) throw new LlmCancelledException();
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
