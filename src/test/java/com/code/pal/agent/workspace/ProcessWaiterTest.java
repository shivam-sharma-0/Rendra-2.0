package com.code.pal.agent.workspace;

import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.testsupport.IsolatedTestJvm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waiting for a real child process: it finishes, times out, or the run is stopped. */
class ProcessWaiterTest {
    private Path script;
    private Process process;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void writeSleeper() throws Exception {
        script = Files.createTempFile("sleeper", ".java");
        Files.writeString(script, "public class Sleeper { public static void main(String[] a) throws Exception { System.out.println(\"READY\"); Thread.sleep(Long.parseLong(a[0])); } }");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (process != null) process.destroyForcibly();
        timer.shutdownNow();
        Files.deleteIfExists(script);
    }

    private Process sleep(long millis) throws Exception {
        process = IsolatedTestJvm.command(script.toString(), Long.toString(millis)).start();
        var ready = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try (var output = process.inputReader(StandardCharsets.UTF_8)) {
                ready.complete(output.readLine());
            } catch (IOException e) {
                ready.completeExceptionally(e);
            }
        });
        boolean started = false;
        try {
            assertEquals("READY", ready.get(15, TimeUnit.SECONDS), "child JVM did not reach Sleeper.main");
            started = true;
        } finally {
            if (!started) process.destroyForcibly();
        }
        return process;
    }

    @Test
    void finishes() throws Exception {
        assertEquals(ProcessWaiter.Outcome.FINISHED, ProcessWaiter.await(sleep(0), Duration.ofSeconds(30)));
    }

    @Test
    void timesOut() throws Exception {
        assertEquals(ProcessWaiter.Outcome.TIMED_OUT, ProcessWaiter.await(sleep(20_000), Duration.ofMillis(300)));
    }

    @Test
    void stopsWaitingWhenTheRunIsCancelled() throws Exception {
        Process sleeper = sleep(20_000);
        RunCancellation run = new RunCancellation(UUID.randomUUID());
        timer.schedule(run::cancel, 300, TimeUnit.MILLISECONDS);
        long start = System.nanoTime();
        try (RunCancellation.Registration ignored = RunCancellation.bind(run)) {
            assertEquals(ProcessWaiter.Outcome.CANCELLED, ProcessWaiter.await(sleeper, Duration.ofSeconds(30)));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed < 1_500, "took " + elapsed + " ms");
    }
}
