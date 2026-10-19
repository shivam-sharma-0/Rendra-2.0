package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupabaseScratchRecoveryTest {
    private final SupabaseScratchResources resources = mock(SupabaseScratchResources.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final AgentProperties properties = new AgentProperties();
    private final SupabaseScratchRecovery recovery = new SupabaseScratchRecovery(resources, leases, properties);
    private final SupabaseScratchResources.Identity identity = new SupabaseScratchResources.Identity(
            "codepal-supa-check-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void prepare() {
        properties.getSupabaseValidation().setRecoveryBudgetMs(60_000);
        when(resources.discover()).thenReturn(List.of(identity.name()));
        when(resources.inspect(identity.name())).thenReturn(identity);
        when(resources.remove(identity)).thenReturn(true);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
                .when(leases).recovering(any(), any(Runnable.class));
    }

    @Test
    void anInactiveOwnedEnvironmentCanBeCleanedEvenAfterRolloutWasDisabled() {
        properties.getSupabaseValidation().setEnabled(false);
        recovery.sweep();
        var order = inOrder(leases, resources);
        order.verify(resources).discover();
        order.verify(resources).inspect(identity.name());
        order.verify(leases).recovering(eq(identity.project()), any(Runnable.class));
        order.verify(leases).hasLiveLease(identity.project());
        order.verify(leases).requireRecovery(identity.project());
        order.verify(resources).inspect(identity.name());
        order.verify(resources).remove(identity);
    }

    @Test
    void anActiveRunIsNeverRemovedBasedOnResourceAgeOrStatus() {
        when(leases.hasLiveLease(identity.project())).thenReturn(true);
        recovery.sweep();
        verify(resources, never()).remove(any());
        verify(leases, never()).requireRecovery(any());
    }

    @Test
    void failedRecoveryOwnershipOrChangedResourceLabelsCannotDeleteAnything() {
        doThrow(new IllegalStateException("Busy project")).when(leases).requireRecovery(identity.project());
        recovery.sweep();
        verify(resources, never()).remove(any());
        doNothing().when(leases).requireRecovery(identity.project());
        when(resources.inspect(identity.name())).thenReturn(identity,
                new SupabaseScratchResources.Identity(identity.name(), UUID.randomUUID(), UUID.randomUUID()));
        recovery.sweep();
        verify(resources, never()).remove(any());
    }

    @Test
    void sweepWorkIsBoundedAndDiscoveryFailureDoesNotInventCleanupProof() {
        properties.getSupabaseValidation().setMaxRecoveryEnvironments(1);
        when(resources.discover()).thenReturn(List.of(identity.name(), "codepal-supa-check-" + UUID.randomUUID()));
        recovery.sweep();
        verify(resources, times(2)).inspect(identity.name());
        verify(resources).remove(identity);
        clearInvocations(resources, leases);
        when(resources.discover()).thenThrow(new IllegalStateException("Docker unavailable"));
        recovery.sweep();
        verifyNoInteractions(leases);
        verify(resources, never()).remove(any());
    }

    @Test
    void aBusyOrUncertainEnvironmentCannotStarveLaterOrphansAcrossBoundedSweeps() {
        properties.getSupabaseValidation().setMaxRecoveryEnvironments(1);
        var orphan = new SupabaseScratchResources.Identity("codepal-supa-check-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(resources.discover()).thenReturn(List.of(identity.name(), orphan.name()));
        when(resources.inspect(orphan.name())).thenReturn(orphan);
        when(resources.remove(orphan)).thenReturn(true);
        when(leases.hasLiveLease(identity.project())).thenReturn(true);
        recovery.sweep();
        verify(resources, never()).remove(any());
        recovery.sweep();
        verify(resources).remove(orphan);
        verify(resources, never()).remove(identity);
    }
}
