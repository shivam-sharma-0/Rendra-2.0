package com.code.pal.message;

import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.ConversationProposals;
import com.code.pal.message.service.impl.MessageServiceImpl;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.domain.RequirementSession;
import com.code.pal.project.domain.RequirementStatus;
import com.code.pal.project.exception.RequirementApprovalRequiredException;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.requirement.BuildRequestIntake;
import com.code.pal.project.requirement.RequirementCoordinator;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.shared.usage.AgentTurnAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildIntakeMessageTest {
    private static final String REQUEST = "i want to buld a end to end with a clean and attractive UI Todo application with all mendatory features, can u build it?";
    private final UUID projectId = UUID.randomUUID();
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final RequirementSessionRepository sessions = mock(RequirementSessionRepository.class);
    private final RequirementCoordinator coordinator = mock(RequirementCoordinator.class);
    private final ProjectFiles files = mock(ProjectFiles.class);
    private final AgentTurnAdmission admission = mock(AgentTurnAdmission.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RealtimeService realtime = mock(RealtimeService.class);
    private final Project project = Project.builder().id(projectId).type(ProjectType.BUILD)
            .initialPrompt("Hi").lifecycleStage(ProjectStage.DESIGN).build();
    private final MessageServiceImpl service = new MessageServiceImpl(messages, projects, realtime, events,
            new MessageSequence(), admission, files, new ConversationProposals(messages), new BuildRequestIntake(coordinator, sessions, files));

    private void ready() {
        when(projects.requireCanEdit(7L, projectId)).thenReturn(project);
        when(messages.save(any(Message.class))).thenAnswer(call -> call.getArgument(0));
        when(messages.countByProjectId(projectId)).thenReturn(2L); // The greeting and its completed reply already exist.
    }

    @Test
    void greetingThenBuildEntersRequirementsWithoutDispatchOrAnExtraAgentCall() {
        ready();
        var result = service.post(7L, projectId, new CreateMessageRequest("actual-build", REQUEST, ProjectType.BUILD, "answer"));
        assertEquals(1, result.size());
        assertEquals(MessageRole.USER, result.getFirst().role());
        assertEquals(ProjectType.BUILD, result.getFirst().mode());
        assertEquals(ProjectStage.REQUIREMENTS, project.getLifecycleStage());
        assertEquals(REQUEST, project.getInitialPrompt());
        verify(sessions).saveAndFlush(argThat(session -> REQUEST.equals(session.getSourcePrompt()) && session.getStatus() == RequirementStatus.DRAFT));
        verify(coordinator).requireNoActiveBuild(projectId);
        verifyNoInteractions(admission, events);
        verify(realtime).sendToProject(eq(projectId), argThat(message -> message.type().equals("PROJECT_UPDATED")));
    }

    @Test
    void existingCodeKeepsItsNormalEditPathAndOriginalRequirements() {
        ready();
        when(files.list(eq(projectId), eq("."), eq(1), anySet())).thenReturn(List.of(new ProjectFiles.Entry("src", true, 0)));
        var result = service.post(7L, projectId, new CreateMessageRequest("edit", "Add a footer", ProjectType.BUILD, "answer"));
        assertEquals(2, result.size());
        assertEquals("Hi", project.getInitialPrompt());
        assertEquals(ProjectStage.DESIGN, project.getLifecycleStage());
        verify(sessions, never()).saveAndFlush(any());
        verify(admission).requireAdmitted(7L);
    }

    @Test
    void explicitChatAndReadOnlyQuestionsDoNotStartAnInterview() {
        ready();
        service.post(7L, projectId, new CreateMessageRequest("question", "Can you explain the build?", ProjectType.BUILD, "answer"));
        service.post(7L, projectId, new CreateMessageRequest("chat", "Build a todo app", ProjectType.CHAT, "chat-answer"));
        assertEquals(ProjectStage.DESIGN, project.getLifecycleStage());
        verifyNoInteractions(coordinator, sessions);
    }

    @Test
    void anExistingApprovedSessionIsNeverReplacedByAnEdit() {
        ready();
        when(sessions.findByProjectId(projectId)).thenReturn(java.util.Optional.of(RequirementSession.builder().status(RequirementStatus.APPROVED).build()));
        var result = service.post(7L, projectId, new CreateMessageRequest("edit", "Add a footer", ProjectType.BUILD, "answer"));
        assertEquals(2, result.size());
        verify(sessions, never()).saveAndFlush(any());
        verifyNoInteractions(files);
    }

    @Test
    void aConcurrentPendingClaimCannotBeReplacedEvenWithAStaleProjectStage() {
        ready();
        when(sessions.findByProjectId(projectId)).thenReturn(java.util.Optional.of(RequirementSession.builder().status(RequirementStatus.DRAFT).build()));
        assertThrows(RequirementApprovalRequiredException.class,
                () -> service.post(7L, projectId, new CreateMessageRequest("second", "Build another app", ProjectType.BUILD, "answer")));
        verify(messages, never()).save(any());
        verifyNoInteractions(admission, events);
    }

    @Test
    void requirementAttachmentsComeFromTheActionableBuildInsteadOfTheGreeting() {
        when(projects.requireAccessible(7L, projectId)).thenReturn(project);
        var build = Message.user(projectId, "actual", REQUEST, ProjectType.BUILD, 7L);
        build.setAttachments("uploads/reference.png");
        when(messages.findFirstByProjectIdAndRoleAndModeOrderBySeqAsc(projectId, MessageRole.USER, ProjectType.BUILD)).thenReturn(java.util.Optional.of(build));
        assertEquals(List.of("uploads/reference.png"), service.openingAttachments(projectId));
        verify(messages, never()).findFirstByProjectIdAndRoleOrderBySeqAsc(any(), any());
    }
}
