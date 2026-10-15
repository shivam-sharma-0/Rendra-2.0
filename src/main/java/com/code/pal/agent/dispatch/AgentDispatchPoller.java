package com.code.pal.agent.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class AgentDispatchPoller {
    private final AgentDispatchRepository outbox;
    private final AgentDispatchService dispatcher;

    @Scheduled(fixedDelayString = "${app.agent.outbox-poll-ms:1000}")
    public void poll() {
        for (var pending : outbox.findTop100ByDispatchedAtIsNullOrderByCreatedAtAsc()) {
            try { dispatcher.dispatch(pending.getRunId()); }
            catch (RuntimeException unavailable) {
                log.warn("agent_dispatch_deferred runId={}", pending.getRunId());
                break; // Store/queue outage must not trigger 100 repeated failing requests.
            }
        }
    }
}
