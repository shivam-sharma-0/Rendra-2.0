package com.code.pal.agent.contract;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.workspace.ProjectFiles;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

/** A failed deterministic attempt still spends its durable signature; no restart/retry loop. */
public final class FixAttempts {
    public record State(String request, String contractHash, Set<String> signatures) {
        public State {
            signatures = Set.copyOf(signatures);
        }
    }

    private final ProjectFiles files;
    private final ProjectLeaseService leases;
    private final ObjectMapper mapper;

    public FixAttempts(ProjectFiles files, ProjectLeaseService leases, ObjectMapper mapper) {
        this.files = files;
        this.leases = leases;
        this.mapper = mapper;
    }

    public boolean claim(AgentRun run, BuildContract contract, DeterministicFixers.Fix fix, Runnable requireCurrent) {
        boolean[] claimed = {false};
        leases.guarded(run.getProjectId(), () -> {
            requireCurrent.run();
            if (run.getRequestFingerprint() == null) {
                throw new IllegalStateException("Fixer requires an accepted request identity");
            }
            String root = ".codepal/fix-attempts/" + run.getId();
            String contractHash = ContractStore.hash(mapper.writeValueAsString(contract));
            var signatures = new TreeSet<String>();
            if (files.exists(run.getProjectId(), root, "attempts.json")) {
                try {
                    String json = new String(files.readBytes(run.getProjectId(), root, "attempts.json", 4_096), StandardCharsets.UTF_8);
                    var state = mapper.readValue(json, State.class);
                    if (!run.getRequestFingerprint().equals(state.request()) || !contractHash.equals(state.contractHash()) || state.signatures().size() > 3) {
                        throw new IllegalStateException("Fixer identity changed");
                    }
                    signatures.addAll(state.signatures());
                } catch (RuntimeException invalid) {
                    throw new IllegalStateException("Fixer attempt proof is unavailable", invalid);
                }
            }
            if (DeterministicFixers.canAttempt(fix, signatures, signatures.size())) {
                signatures.add(fix.signature());
                files.writeText(run.getProjectId(), root, "attempts.json",
                        mapper.writeValueAsString(new State(run.getRequestFingerprint(), contractHash, signatures)));
                claimed[0] = true;
            }
        });
        return claimed[0];
    }
}
