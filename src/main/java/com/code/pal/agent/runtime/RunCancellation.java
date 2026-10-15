package com.code.pal.agent.runtime;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A run's Stop signal, shared by everything that works for the run: the agent loop, model calls and
 * sandbox commands. Cancelling it is immediate and happens once; work that is waiting (a model stream,
 * a command) polls {@link #isCancelled()} or registers an {@link #onCancel hook} that interrupts it.
 *
 * <p>The worker binds the run's cancellation to its thread ({@link #bind}), so lower layers find it with
 * {@link #current()} without every API carrying it. Code outside a run sees {@link #NONE}, which is
 * never cancelled.
 */
@Slf4j
public final class RunCancellation {

    /** For work that is not part of a run: never cancelled. */
    public static final RunCancellation NONE = new RunCancellation(null);

    private static final ThreadLocal<RunCancellation> CURRENT = new ThreadLocal<>();

    private final UUID runId;
    private enum Reason { CANCELLED, OWNERSHIP_LOST }
    private final AtomicReference<Reason> reason = new AtomicReference<>();
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    public RunCancellation(UUID runId) {
        this.runId = runId;
    }

    public UUID runId() {
        return runId;
    }

    public boolean isCancelled() {
        return reason.get() != null;
    }

    /** Cancels once; runs every registered hook on the calling thread. No effect on {@link #NONE}. */
    public void cancel() {
        cancel(Reason.CANCELLED);
    }

    public void loseOwnership() {
        cancel(Reason.OWNERSHIP_LOST);
    }

    public boolean isOwnershipLost() {
        return reason.get() == Reason.OWNERSHIP_LOST;
    }

    private void cancel(Reason cause) {
        if (this == NONE || !reason.compareAndSet(null, cause)) return;
        for (Runnable hook : hooks) runQuietly(hook);
    }

    /**
     * Runs {@code hook} when the run is cancelled (at once if it already is) until the returned handle
     * is closed. Hooks run on the cancelling thread, so they must be short and must not wait for the run.
     */
    public Registration onCancel(Runnable hook) {
        if (this == NONE) return () -> { };
        hooks.add(hook);
        if (isCancelled() && hooks.remove(hook)) runQuietly(hook);
        return () -> hooks.remove(hook);
    }

    /** The cancellation bound to this thread, or {@link #NONE}. */
    public static RunCancellation current() {
        RunCancellation bound = CURRENT.get();
        return bound == null ? NONE : bound;
    }

    /** Binds {@code cancellation} to this thread until the returned scope is closed (restores the previous one). */
    public static Registration bind(RunCancellation cancellation) {
        RunCancellation previous = CURRENT.get();
        CURRENT.set(cancellation);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    private void runQuietly(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            log.warn("cancel_hook_failed runId={} error={}", runId, e.getMessage());
        }
    }

    /** A hook registration or a thread binding; closing it never throws. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
