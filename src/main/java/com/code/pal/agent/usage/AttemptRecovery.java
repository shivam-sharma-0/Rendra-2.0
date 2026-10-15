package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Slf4j @Component
public class AttemptRecovery {
    private final DailyTokenBudget budget;
    private final AgentProperties props;
    public AttemptRecovery(DailyTokenBudget budget,AgentProperties props) { this.budget=budget;this.props=props; }
    @Scheduled(fixedDelayString="${app.agent.accounting-recovery-poll-ms:30000}")
    public void sweep() {
        Instant before=Instant.now().minusMillis(Math.max(60_000,props.getAccountingRecoveryAgeMs()));
        try {
            for(var id:budget.pendingRecovery(before,100)) {
                try { budget.recover(id,before); }
                catch(RuntimeException unavailable) { log.warn("model_attempt_recovery_pending attemptId={}",id); }
            }
        } catch(RuntimeException unavailable) { log.warn("model_attempt_recovery_store_unavailable"); }
    }
}
