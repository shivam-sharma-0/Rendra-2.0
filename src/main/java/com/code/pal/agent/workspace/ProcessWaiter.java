package com.code.pal.agent.workspace;

import com.code.pal.agent.runtime.RunCancellation;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Waits for a sandbox process like {@link Process#waitFor(long, TimeUnit)}, but also stops waiting when the run is cancelled. */
final class ProcessWaiter {

    enum Outcome { FINISHED, TIMED_OUT, CANCELLED }

    private static final long POLL_MS = 100;

    private ProcessWaiter() {
    }

    /** Polls {@code process} until it exits, {@code timeout} passes, or {@link RunCancellation#current()} is cancelled. */
    static Outcome await(Process process, Duration timeout) throws InterruptedException {
        RunCancellation cancellation = RunCancellation.current();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (cancellation.isCancelled()) return Outcome.CANCELLED;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return Outcome.TIMED_OUT;
            if (process.waitFor(Math.min(POLL_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS)) {
                return Outcome.FINISHED;
            }
        }
    }
}
