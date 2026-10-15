package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixAttemptsTest {
    @Test
    void claimsAreDurableUniqueAndBoundedEvenWhenExecutionFails() {
        var project = LocalProject.create();
        var fixture = new ContractCoordinatorTest();
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var leases = mock(ProjectLeaseService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(any(), any(Runnable.class));
        var attempts = new FixAttempts(project.files, leases, new ObjectMapper());
        for (int number = 0; number < 4; number++) {
            var fix = new DeterministicFixers.Fix(Map.of("index.html", "fixed"), false, ContractStore.hash("defect-" + number));
            assertEquals(number < 3, attempts.claim(run, fixture.c, fix, () -> {}));
            attempts = new FixAttempts(project.files, leases, new ObjectMapper());
            assertFalse(attempts.claim(run, fixture.c, fix, () -> {}));
        }
    }

    @Test
    void stopBeforeClaimDoesNotExecuteAFixer() {
        var project = LocalProject.create();
        var fixture = new ContractCoordinatorTest();
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var leases = mock(ProjectLeaseService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(any(), any(Runnable.class));
        var attempts = new FixAttempts(project.files, leases, new ObjectMapper());
        assertThrows(IllegalStateException.class, () -> attempts.claim(run, fixture.c,
                new DeterministicFixers.Fix(Map.of("index.html", "fixed"), false, ContractStore.hash("defect")),
                () -> { throw new IllegalStateException("Stop"); }));
    }
}
