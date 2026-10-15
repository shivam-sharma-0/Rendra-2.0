package com.code.pal.agent.contract;

import com.code.pal.agent.tool.AgentContext;
import java.util.*;

/** Backend-constructed capability. Worker code generation has no mutation/command authority. */
public final class UnitWriteScope {
    private record Policy(UUID project, UUID run, Set<String> writes, Set<String> reads, boolean worker) {
    }

    private static final ThreadLocal<Policy> CURRENT = new ThreadLocal<>();

    private UnitWriteScope() {
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static Scope bind(Policy policy) {
        var old = CURRENT.get();
        CURRENT.set(policy);
        return () -> {
            if (old == null)
                CURRENT.remove();
            else
                CURRENT.set(old);
        };
    }

    public static Scope worker(AgentContext ctx) {
        return bind(new Policy(ctx.projectId(), ctx.runId(), Set.of(), Set.of(), true));
    }

    public static Scope wave(AgentContext ctx, BuildContract c, List<BuildContract.Unit> units) {
        var writes = new HashSet<String>();
        var reads = new HashSet<String>();
        var ids = new HashSet<String>();
        for (var u : units) {
            if (!c.units().contains(u))
                throw new IllegalArgumentException("Unit is not part of immutable contract");
            writes.addAll(u.ownedPaths());
            reads.addAll(u.imports());
            ids.add(u.id());
        }
        reads.addAll(writes);
        c.assets().stream().filter(a -> ids.contains(a.owner())).forEach(a -> reads.add(a.source()));
        return bind(new Policy(ctx.projectId(), ctx.runId(), Set.copyOf(writes), Set.copyOf(reads), false));
    }

    public static void rejectWorkerIo() {
        var p = CURRENT.get();
        if (p != null && p.worker())
            throw new IllegalStateException("Generation workers cannot mutate files or execute commands");
    }

    public static void destination(AgentContext ctx, String path) {
        var p = CURRENT.get();
        if (p == null)
            return;
        rejectWorkerIo();
        if (!p.project().equals(ctx.projectId()) || !p.run().equals(ctx.runId()) || !p.writes().contains(path))
            throw new IllegalStateException("Mutation exceeds unit ownership");
    }

    public static void source(AgentContext ctx, String path) {
        var p = CURRENT.get();
        if (p == null)
            return;
        rejectWorkerIo();
        if (!p.project().equals(ctx.projectId()) || !p.run().equals(ctx.runId()) || !p.reads().contains(path))
            throw new IllegalStateException("Copy source exceeds declared unit context");
    }
}
