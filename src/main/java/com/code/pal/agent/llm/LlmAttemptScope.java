package com.code.pal.agent.llm;

import java.util.function.Supplier;

/** The primary gateway accounts each actual transport attempt, including internal retries. */
public final class LlmAttemptScope {
    @FunctionalInterface public interface Observer {
        LlmClient.LlmResult execute(LlmClient.LlmRequest request, Supplier<LlmClient.LlmResult> transmission);
    }
    private static final ThreadLocal<Observer> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Runnable> RESPONSE_CLOSED = new ThreadLocal<>();
    public static Scope onResponseClosed(Runnable listener){var before=RESPONSE_CLOSED.get();RESPONSE_CLOSED.set(listener);return ()->{if(before==null)RESPONSE_CLOSED.remove();else RESPONSE_CLOSED.set(before);};}
    /** Transport-authored receipt; no response body, key or model text can supply this signal. */
    public static void responseClosed(){var listener=RESPONSE_CLOSED.get();if(listener!=null)listener.run();}
    private LlmAttemptScope() { }

    public static Scope bind(Observer observer) {
        Observer previous = CURRENT.get();
        CURRENT.set(observer);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public static LlmClient.LlmResult execute(LlmClient.LlmRequest request,
                                             Supplier<LlmClient.LlmResult> transmission, boolean required) {
        Observer observer = CURRENT.get();
        if (observer == null) {
            if (required) throw new LlmAccountingException("Provider transmission has no accounting ownership");
            return transmission.get(); // Standalone transport fixtures; managed runtime always requires scope.
        }
        return observer.execute(request, transmission);
    }

    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
