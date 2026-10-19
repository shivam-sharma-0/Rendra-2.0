package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Recover only owned inactive environments, serialized with lease takeover and fenced resource creation. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.agent.sandbox", name = "required", havingValue = "true")
public final class SupabaseScratchRecovery {
    private final SupabaseScratchResources resources;
    private final ProjectLeaseService leases;
    private final AgentProperties properties;
    private int nextIndex;

    @Autowired
    public SupabaseScratchRecovery(DockerSupabaseScratch scratch, ProjectLeaseService leases, AgentProperties properties) {
        this(scratch.resources(), leases, properties);
    }

    SupabaseScratchRecovery(SupabaseScratchResources resources, ProjectLeaseService leases, AgentProperties properties) {
        this.resources = resources;
        this.leases = leases;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.agent.supabase-validation.recovery-interval-ms:60000}")
    public synchronized void sweep() {
        var settings = properties.getSupabaseValidation();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, settings.getRecoveryBudgetMs()));
        int processed = 0;
        try {
            var names = resources.discover();
            int start = names.isEmpty() ? 0 : Math.floorMod(nextIndex, names.size());
            while (processed < names.size() && processed < Math.max(1, settings.getMaxRecoveryEnvironments()) && System.nanoTime() < deadline) {
                int index = (start + processed++) % names.size();
                String name = names.get(index);
                nextIndex = (index + 1) % names.size();
                try {
                    var expected = resources.inspect(name);
                    leases.recovering(expected.project(), () -> {
                        if (leases.hasLiveLease(expected.project())) return;
                        leases.requireRecovery(expected.project());
                        // Prove ownership again under the same critical section that fences all new resource mutations.
                        if (!expected.equals(resources.inspect(name)) || !resources.remove(expected)) {
                            throw new SupabaseScratchException("Isolated Supabase cleanup remains unverified", false);
                        }
                    });
                } catch (RuntimeException pending) {
                    log.warn("supabase_scratch_recovery_pending environment={} errorType={}", name, pending.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            log.debug("supabase_scratch_discovery_unavailable errorType={}", unavailable.getClass().getSimpleName());
        }
    }
}
