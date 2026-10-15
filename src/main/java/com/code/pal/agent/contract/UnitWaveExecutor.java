package com.code.pal.agent.contract;

import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** At most two generation workers. Complete barrier or failure; workers never merge/build. */
@Component
public final class UnitWaveExecutor {
    private record Completed(int index, UnitGenerator.Output output) {
    }

    public List<UnitGenerator.Output> generate(List<UnitGenerator.Input> inputs, int width, long remainingMs,
            Function<UnitGenerator.Input, UnitGenerator.Output> work) {
        if (width < 1 || width > 2 || inputs.size() > 12 || remainingMs <= 0)
            throw new IllegalArgumentException("Invalid bounded wave");
        var frozen = List.copyOf(inputs);
        if (width == 1) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);
            var out = new ArrayList<UnitGenerator.Output>();
            for (var input : frozen) {
                if (RunCancellation.current().isCancelled())
                    throw new LlmCancelledException();
                if (System.nanoTime() >= deadline)
                    throw new LlmException("Unit wave deadline reached");
                out.add(work.apply(input));
            }
            if (System.nanoTime() >= deadline)
                throw new LlmException("Unit wave deadline reached");
            return List.copyOf(out);
        }
        var parent = RunCancellation.current();
        var wave = new RunCancellation(parent.runId());
        var fence = RunFence.current();
        var caller = LlmCallContext.current();
        var pool = Executors.newFixedThreadPool(2, Thread.ofVirtual().name("unit-generation-", 0).factory());
        var completions = new ExecutorCompletionService<Completed>(pool);
        var futures = new ArrayList<Future<Completed>>();
        var firstFailure = new AtomicReference<RuntimeException>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);
        var outputs = new UnitGenerator.Output[frozen.size()];
        try (var stop = parent.onCancel(wave::cancel)) {
            for (int i = 0; i < frozen.size(); i++) {
                final int position = i;
                futures.add(completions.submit(() -> {
                    try (var cancel = RunCancellation.bind(wave);
                            var owner = RunFence.bind(fence);
                            var identity = LlmCallContext.bind(caller)) {
                        if (wave.isCancelled())
                            throw new LlmCancelledException();
                        var out = work.apply(frozen.get(position));
                        if (wave.isCancelled() || Thread.currentThread().isInterrupted())
                            throw new LlmCancelledException();
                        return new Completed(position, out);
                    } catch (RuntimeException failed) {
                        firstFailure.compareAndSet(null, failed);
                        wave.cancel();
                        throw failed;
                    }
                }));
            }
            int done = 0;
            while (done < frozen.size()) {
                if (parent.isCancelled())
                    throw new LlmCancelledException();
                if (firstFailure.get() != null)
                    throw firstFailure.get();
                long left = deadline - System.nanoTime();
                if (left <= 0)
                    throw new LlmException("Unit wave deadline reached; no outputs merged");
                Future<Completed> future = completions.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS);
                if (future == null)
                    continue;
                try {
                    var result = future.get();
                    outputs[result.index()] = result.output();
                    done++;
                } catch (ExecutionException failed) {
                    throw firstFailure.get() == null ? new LlmException("Unit generation failed", failed)
                            : firstFailure.get();
                }
            }
            if (parent.isCancelled())
                throw new LlmCancelledException();
            return List.of(outputs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmCancelledException();
        } finally {
            wave.cancel();
            futures.forEach(f -> f.cancel(true));
            pool.shutdownNow();
        }
    }
}
