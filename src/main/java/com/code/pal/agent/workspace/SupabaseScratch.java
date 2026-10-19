package com.code.pal.agent.workspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An isolated schema/auth/REST environment; never accepts a customer URL, token or database credential. */
public interface SupabaseScratch {
    record Migration(String name, String sql) {}

    Session open(UUID project, UUID run, List<Migration> migrations);

    interface Session extends AutoCloseable {
        SandboxPort.ServerEndpoint endpoint();
        String publicKey();
        Map<String, String> buildVariables();
        void restartRest();
        @Override void close();
    }
}
