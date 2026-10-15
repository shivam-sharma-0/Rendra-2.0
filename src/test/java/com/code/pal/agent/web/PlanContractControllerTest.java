package com.code.pal.agent.web;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanContractControllerTest {
    @Test void disabledFeatureDoesNotReadContractsAndCannotApprove() {
        var store=mock(ContractStore.class);var projects=mock(ProjectService.class);var props=new AgentProperties();
        var controller=new PlanContractController(store,projects,props);var principal=mock(AuthPrincipal.class);when(principal.userId()).thenReturn(7L);
        var project=UUID.randomUUID();var run=UUID.randomUUID();
        assertNull(controller.view(principal,project,run));
        assertThrows(RuntimeException.class,()->controller.approve(principal,project,run,new PlanContractController.Approval(UUID.randomUUID(),"hash")));
        verify(projects).requireAccessible(7L,project);verify(projects).requireCanEdit(7L,project);verifyNoInteractions(store);
    }
    @Test void permissionFailureStopsApprovalBeforeContractMutation() {
        var store=mock(ContractStore.class);var projects=mock(ProjectService.class);var props=new AgentProperties();props.setStructuredContracts(true);
        var controller=new PlanContractController(store,projects,props);var principal=mock(AuthPrincipal.class);when(principal.userId()).thenReturn(7L);
        var project=UUID.randomUUID();doThrow(new IllegalStateException("revoked")).when(projects).requireCanEdit(7L,project);
        assertThrows(RuntimeException.class,()->controller.approve(principal,project,UUID.randomUUID(),new PlanContractController.Approval(UUID.randomUUID(),"hash")));
        verifyNoInteractions(store);
    }
}
