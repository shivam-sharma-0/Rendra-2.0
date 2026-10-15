package com.code.pal.agent.web;

import com.code.pal.agent.change.*;
import com.code.pal.agent.exception.AgentRunNotFoundException;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailedDraftControllerTest {
    @Test void unauthorizedAndForeignRunRequestsNeverReadSource() {
        var projects=mock(ProjectService.class);var runs=mock(AgentRunRepository.class);var drafts=mock(FailedDraftService.class);
        var controller=new FailedDraftController(projects,runs,drafts,mock(FailedDraftRetryService.class));
        var user=new AuthPrincipal(7L,UUID.randomUUID(),"fixture@example.invalid","USER");UUID project=UUID.randomUUID(),run=UUID.randomUUID();
        doThrow(new com.code.pal.workspace.exception.WorkspaceAccessDeniedException()).when(projects).requireAccessible(7L,project);
        assertThrows(com.code.pal.workspace.exception.WorkspaceAccessDeniedException.class,()->controller.download(user,project,run));verifyNoInteractions(runs,drafts);
        reset(projects);
        assertThrows(AgentRunNotFoundException.class,()->controller.file(user,project,run,"App.tsx"));verifyNoInteractions(drafts);
    }
}
