package com.code.pal.agent.change;

/** The accepted files cannot yet be certified. Keep COMMITTING and all recovery evidence. */
public final class PromotionRecoveryPendingException extends RuntimeException {
    public PromotionRecoveryPendingException(Throwable cause) {
        super("Code save needs recovery verification; project writes are paused", cause);
    }
}
