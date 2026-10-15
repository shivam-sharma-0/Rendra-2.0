package com.code.pal.testsupport;

import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.runtime.RunCancellation;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A finite, network-free script. Unexpected extra calls fail instead of hiding repair loops. */
public final class ScriptedLlmClient implements LlmClient {
    private interface Outcome { LlmResult execute(Consumer<String> deltas); }
    private final Queue<Outcome> outcomes = new ArrayDeque<>();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    public synchronized ScriptedLlmClient answer(String text, Usage usage) {
        outcomes.add(deltas -> {
            deltas.accept(text);
            return new LlmResult(text, null, List.of(), usage, "stop");
        });
        return this;
    }

    public synchronized ScriptedLlmClient result(LlmResult result) {
        outcomes.add(deltas -> result);
        return this;
    }

    public synchronized ScriptedLlmClient httpError(int status) {
        outcomes.add(deltas -> { throw new LlmHttpException(status, "Scripted provider failure"); });
        return this;
    }

    public synchronized ScriptedLlmClient waitForCancellation(CountDownLatch entered, Duration timeout) {
        outcomes.add(deltas -> {
            CountDownLatch cancelled = new CountDownLatch(1);
            try (var ignored = RunCancellation.current().onCancel(cancelled::countDown)) {
                entered.countDown();
                if (!cancelled.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    throw new AssertionError("Scripted call was not cancelled before its deadline");
                throw new LlmCancelledException();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LlmCancelledException();
            }
        });
        return this;
    }

    public List<LlmRequest> requests() { return List.copyOf(requests); }

    @Override
    public LlmResult chatStreaming(LlmRequest request, Consumer<String> deltas) {
        if (RunCancellation.current().isCancelled()) throw new LlmCancelledException();
        Outcome outcome;
        synchronized (this) { outcome = outcomes.poll(); }
        if (outcome == null) throw new AssertionError("Unexpected provider call: script exhausted");
        requests.add(request);
        return outcome.execute(deltas);
    }
}
