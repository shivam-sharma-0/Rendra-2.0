package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.service.ProjectService;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecBindingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalProject project = LocalProject.create();
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final BuildDecisions decisions = mock(BuildDecisions.class);
    private final ContractStore store = new ContractStore(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class), mapper,
            project.files, memory, decisions, mock(ProjectLeaseService.class), mock(ProjectService.class));
    private final UUID projection = UUID.randomUUID();

    SpecBindingTest() {
        when(decisions.state(project.projectId)).thenReturn(new BuildDecisions.State(
                new BuildDecisions.Choice(1, BuildDecisions.Storage.NONE, BuildDecisions.Auth.NONE, null),
                new BuildDecisions.Available(true, null), null));
    }

    @Test
    void v2BindingIncludesApprovedSpecAndCurrentScaffoldIdentity() {
        String body = mapper.writeValueAsString(SpecFixtures.approved().spec());
        when(memory.current(project.projectId)).thenReturn(new ApprovedProjectMemory.Approved(projection, 2, "{}", "Brief", body));
        var binding = store.current(project.projectId);
        assertEquals(2, binding.version());
        assertNotNull(binding.specId());
        assertEquals(3L, binding.specRevision().longValue());
        assertEquals("sha256:" + ContractStore.hash(body), binding.specHash());
        assertEquals(Starter.TEMPLATE_VERSION, binding.templateVersion());
        assertEquals(ContractScaffold.revision(), binding.scaffoldRevision());
        assertEquals(binding, mapper.readValue(mapper.writeValueAsString(binding), ContractStore.Binding.class));
        String next = body.replace("Solar Bakery", "New Bakery");
        when(memory.current(project.projectId)).thenReturn(new ApprovedProjectMemory.Approved(projection, 2, "{}", "Brief", next));
        assertNotEquals(binding, store.current(project.projectId), "Even an inconsistent same-revision document change must invalidate reuse");
    }

    @Test
    void legacyBindingStillRoundTripsWithNoV2Metadata() {
        when(memory.current(project.projectId)).thenReturn(new ApprovedProjectMemory.Approved(projection, 2, "{}", "Brief"));
        var binding = store.current(project.projectId);
        assertEquals(1, binding.version());
        String json = mapper.writeValueAsString(binding);
        assertFalse(mapper.readTree(json).has("specRevision"));
        assertEquals(binding, mapper.readValue(json, ContractStore.Binding.class));
    }
}
