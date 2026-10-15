package com.code.pal.agent.insight;

import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.usage.DailyTokenBudget;
import com.code.pal.agent.workspace.WorkspaceBrowser;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectInsightServiceTest {
    private final ProjectService projects=mock(ProjectService.class);
    private final AgentRunRepository runs=mock(AgentRunRepository.class);
    private final MessageRepository messages=mock(MessageRepository.class);
    private final WorkspaceBrowser browser=mock(WorkspaceBrowser.class);
    private final DailyTokenBudget budget=mock(DailyTokenBudget.class);
    private final ProjectInsightService service=new ProjectInsightService(projects,runs,messages,browser,budget);
    @Test void authorizationPrecedesAccountingRead() {
        UUID project=UUID.randomUUID();when(projects.get(7L,project)).thenThrow(new IllegalArgumentException("Denied"));
        assertThrows(IllegalArgumentException.class,()->service.analytics(7L,project));verifyNoInteractions(budget,runs,messages,browser);
    }
    @Test void ledgerTotalsReplaceRunDisplayCounters() {
        UUID project=UUID.randomUUID();var p=mock(ProjectResponse.class);when(p.deploymentStatus()).thenReturn(DeploymentStatus.NONE);
        when(projects.get(7L,project)).thenReturn(p);when(browser.tree(project)).thenReturn(List.of());
        when(budget.projectUsage(project)).thenReturn(new DailyTokenBudget.ProjectUsage(100,200,300,2,4,3,1));
        var result=service.analytics(7L,project);
        assertEquals(300,result.totalTokens());assertEquals(100,result.reportedTokens());assertEquals(200,result.unknownTokens());
        assertEquals(300,result.reservedTokens());assertEquals(4,result.actualAttempts());assertEquals(3,result.logicalCalls());
        verify(runs,never()).sumTokensByProjectId(project);
    }
}
