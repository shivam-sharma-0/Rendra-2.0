package com.code.pal.agent.contract;

import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.agent.validation.ValidationOperation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.code.pal.agent.validation.ValidationCheck.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidationBundlesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID run = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID contract = UUID.randomUUID();
    private final Map<String, ValidationBundle.CommandReceipt> receipts = new HashMap<>();
    private final ValidationBundles bundles = new ValidationBundles(mapper, new ValidationBundles.Receipts() {
        public ValidationBundle.CommandReceipt find(ValidationRequest request, String key) { return receipts.get(key); }
        public void save(ValidationRequest request, String key, ValidationBundle.CommandReceipt value) { receipts.put(key, value); }
    });
    private final AtomicInteger commands = new AtomicInteger();
    private final AtomicInteger finalChecks = new AtomicInteger();

    private ValidationRequest request(ValidationRequest.Phase phase, String dependencies, String criteria) {
        return new ValidationRequest(run, project, contract, 1, phase == ValidationRequest.Phase.BATCH ? "app" : null,
                3, "source", dependencies, "commands", criteria, "capabilities", "validator-v1", phase,
                phase == ValidationRequest.Phase.BATCH ? Set.of("TYPE_CHECK", "BUILD") : Set.of("TYPE_CHECK", "BUILD", "RUNTIME", "BASELINE"),
                Set.of("app"));
    }

    private ValidationBundles.Adapter adapter(ValidationCheck.Outcome outcome, boolean artifactPresent) {
        return new ValidationBundles.Adapter() {
            public ValidationBundle.CommandReceipt command(ValidationRequest request, String id) {
                commands.incrementAndGet();
                return new ValidationBundle.CommandReceipt(id, bundles.commandIdentity(request, id), outcome,
                        id.equals("BUILD") && outcome == PASS ? "artifact" : null);
            }
            public boolean artifactPresent(ValidationBundle.CommandReceipt command) { return artifactPresent; }
            public ValidationReport finish(ValidationRequest request, List<ValidationBundle.CommandReceipt> receipts) {
                finalChecks.incrementAndGet();
                return new ValidationReport(ValidationOperation.BUILD, true, true, true, false, 0,
                        "npm", "fixture", List.of(), List.of(), 0, 2, request.stagedRevision(), "Fixture", List.of(
                                new ValidationCheck("RUNTIME", PASS, true, "Fixture"), new ValidationCheck("BASELINE", PASS, true, "Fixture")));
            }
        };
    }

    @Test
    void finalAndRestartReuseCompilerReceiptsButRerunAcceptance() {
        var batch = bundles.validateBundle(request(ValidationRequest.Phase.BATCH, "deps", "criteria"), adapter(PASS, true));
        assertFalse(batch.certifyEligible());
        assertEquals(2, commands.get());
        var result = bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "deps", "criteria"), adapter(PASS, true));
        assertTrue(result.certifyEligible());
        assertEquals(2, commands.get());
        assertEquals(2, finalChecks.get());
        assertNotEquals(batch.diagnosticsRevision(), result.diagnosticsRevision());
    }

    @Test
    void dependenciesCriteriaAndMissingArtifactInvalidateEvidence() {
        bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "deps", "criteria"), adapter(PASS, true));
        bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "new-deps", "criteria"), adapter(PASS, true));
        bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "new-deps", "new-criteria"), adapter(PASS, true));
        assertEquals(6, commands.get());
        assertFalse(bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "new-deps", "new-criteria"),
                adapter(PASS, false)).certifyEligible());
        assertEquals(7, commands.get(), "Only the missing build artifact must run again");
    }

    @Test
    void skippedAndFailedPrerequisitesNeverCertifyOrCache() {
        for (var outcome : List.of(FAIL, SKIPPED)) {
            assertFalse(bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "deps", outcome.name()), adapter(outcome, false)).certifyEligible());
            assertFalse(bundles.validateBundle(request(ValidationRequest.Phase.FINAL, "deps", outcome.name()), adapter(outcome, false)).certifyEligible());
        }
        assertTrue(receipts.isEmpty());
        assertEquals(4, commands.get(), "BUILD is not run after a failed/skipped required typecheck");
    }

    @Test
    void duplicateOrForeignCommandEvidenceFailsClosed() {
        var request = request(ValidationRequest.Phase.FINAL, "deps", "criteria");
        String key = bundles.commandIdentity(request, "TYPE_CHECK");
        receipts.put(key, new ValidationBundle.CommandReceipt("BUILD", key, PASS, "artifact"));
        assertThrows(IllegalStateException.class, () -> bundles.validateBundle(request, adapter(PASS, true)));
        var checks = List.of(new ValidationCheck("BUILD", PASS, true, ""), new ValidationCheck("BUILD", PASS, true, ""));
        assertFalse(new ValidationBundle(request, "diagnostics", checks, List.of(), List.of(), "artifact", true).certifyEligible());
    }
}
