package com.code.pal.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCancellationTest {

    @Test
    void cancelsOnceAndRunsHooksOnce() {
        RunCancellation run = new RunCancellation(UUID.randomUUID());
        AtomicInteger calls = new AtomicInteger();
        run.onCancel(calls::incrementAndGet);
        run.cancel();
        run.cancel();
        assertTrue(run.isCancelled());
        assertEquals(1, calls.get());
    }

    @Test
    void aHookAddedAfterCancelRunsAtOnceAndAClosedOneNever() {
        RunCancellation run = new RunCancellation(UUID.randomUUID());
        AtomicInteger closed = new AtomicInteger();
        run.onCancel(closed::incrementAndGet).close();
        run.cancel();
        assertEquals(0, closed.get());
        AtomicInteger late = new AtomicInteger();
        run.onCancel(late::incrementAndGet);
        assertEquals(1, late.get());
    }

    @Test
    void aFailingHookDoesNotStopTheOthers() {
        RunCancellation run = new RunCancellation(UUID.randomUUID());
        AtomicInteger after = new AtomicInteger();
        run.onCancel(() -> { throw new IllegalStateException("boom"); });
        run.onCancel(after::incrementAndGet);
        run.cancel();
        assertEquals(1, after.get());
    }

    @Test
    void bindingIsPerThreadAndRestored() throws Exception {
        assertSame(RunCancellation.NONE, RunCancellation.current());
        RunCancellation outer = new RunCancellation(UUID.randomUUID());
        RunCancellation inner = new RunCancellation(UUID.randomUUID());
        try (RunCancellation.Registration a = RunCancellation.bind(outer)) {
            try (RunCancellation.Registration b = RunCancellation.bind(inner)) {
                assertSame(inner, RunCancellation.current());
                RunCancellation[] seen = new RunCancellation[1];
                Thread other = new Thread(() -> seen[0] = RunCancellation.current());
                other.start();
                other.join();
                assertSame(RunCancellation.NONE, seen[0]);
            }
            assertSame(outer, RunCancellation.current());
        }
        assertSame(RunCancellation.NONE, RunCancellation.current());
    }

    @Test
    void noneIsNeverCancelled() {
        RunCancellation.NONE.cancel();
        assertFalse(RunCancellation.NONE.isCancelled());
    }
}
