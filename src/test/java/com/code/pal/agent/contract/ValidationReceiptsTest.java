package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.workspace.LocalProject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ValidationReceiptsTest {
    @Test
    void durableReceiptSurvivesServiceRestartAndRejectsCorruption() {
        var project = LocalProject.create();
        var leases = mock(ProjectLeaseService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(eq(project.projectId), any(Runnable.class));
        var mapper = new ObjectMapper();
        var request = new ValidationRequest(UUID.randomUUID(), project.projectId, UUID.randomUUID(), 1, null, 2,
                "source", "deps", "commands", "criteria", "caps", "validator", ValidationRequest.Phase.FINAL, Set.of("BUILD"), Set.of("app"));
        String key = "a".repeat(64);
        var value = new ValidationBundle.CommandReceipt("BUILD", key, ValidationCheck.Outcome.PASS, "artifact");
        var store = new ValidationReceipts(project.files, mapper, leases, identity -> {});
        store.save(request, key, value);
        var restarted = new ValidationReceipts(project.files, mapper, leases, identity -> {});
        assertEquals(value, restarted.find(request, key));
        project.files.writeText(project.projectId, ".codepal/validation-receipts/" + request.runId(), key + ".json", "{}");
        assertThrows(IllegalStateException.class, () -> restarted.find(request, key));
    }

    @Test
    void stoppedOrStaleOwnerCannotSaveCompilerEvidence() {
        var project = LocalProject.create();
        var leases = mock(ProjectLeaseService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(eq(project.projectId), any(Runnable.class));
        var request = new ValidationRequest(UUID.randomUUID(), project.projectId, UUID.randomUUID(), 1, null, 2,
                "source", "deps", "commands", "criteria", "caps", "validator", ValidationRequest.Phase.FINAL, Set.of("BUILD"), Set.of());
        var store = new ValidationReceipts(project.files, new ObjectMapper(), leases, identity -> { throw new IllegalStateException("Stop"); });
        String key = "b".repeat(64);
        assertThrows(IllegalStateException.class, () -> store.save(request, key,
                new ValidationBundle.CommandReceipt("BUILD", key, ValidationCheck.Outcome.PASS, "artifact")));
        assertFalse(project.files.exists(project.projectId, ".codepal/validation-receipts/" + request.runId(), key + ".json"));
    }
}
