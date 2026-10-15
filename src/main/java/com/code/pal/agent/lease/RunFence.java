package com.code.pal.agent.lease;

import java.util.UUID;

/** Internal worker identity; model arguments never construct or bind this capability. */
public record RunFence(UUID projectId, UUID runId, UUID ownerId, long epoch) {
    private static final ThreadLocal<RunFence> CURRENT = new ThreadLocal<>();
    public static RunFence current() { return CURRENT.get(); }
    public static Scope bind(RunFence fence) {
        RunFence previous=CURRENT.get();CURRENT.set(fence);
        return ()->{if(previous==null) CURRENT.remove();else CURRENT.set(previous);};
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
