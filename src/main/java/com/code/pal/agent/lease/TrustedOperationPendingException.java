package com.code.pal.agent.lease;

public final class TrustedOperationPendingException extends RuntimeException {
    public TrustedOperationPendingException(){super("Trusted project operation cleanup is pending; writes are quarantined");}
}
